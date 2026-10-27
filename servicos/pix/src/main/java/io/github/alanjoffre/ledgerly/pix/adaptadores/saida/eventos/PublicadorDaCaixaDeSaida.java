package io.github.alanjoffre.ledgerly.pix.adaptadores.saida.eventos;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;
import org.jboss.logging.Logger;

/**
 * O publicador do Outbox do Pix (ADR-0013) — o mesmo algoritmo do razão, no jeito Quarkus:
 * a cada 100 ms, numa transação, pega um lote de pendentes ({@code FOR UPDATE SKIP LOCKED}), manda
 * ao Kafka, <b>espera a confirmação</b> e marca como publicados. Kafka fora do ar: desfaz e tenta
 * na próxima rodada.
 *
 * <p>O {@link Emitter} é a ponte do SmallRye Reactive Messaging para o Kafka: cada mensagem leva
 * uma função de confirmação ({@code ack}) e uma de falha ({@code nack}), chamadas quando o Kafka
 * responde.
 *
 * <p>O agendador do Quarkus ({@code @Scheduled}) não aceita intervalo menor que 1 s; por isso a
 * rodada de 100 ms tem o próprio relógio: uma <i>virtual thread</i> que roda, espera 100 ms e roda
 * de novo, do início ao fim da aplicação.
 */
@ApplicationScoped
class PublicadorDaCaixaDeSaida {

    private static final Logger LOG = Logger.getLogger(PublicadorDaCaixaDeSaida.class);

    private final DataSource banco;
    private final Emitter<String> kafka;
    private final int lote;
    private final long esperaMaximaMs;
    private final long intervaloMs;
    private ScheduledExecutorService relogio;

    PublicadorDaCaixaDeSaida(
            DataSource banco,
            @Channel("pix-transferencias") Emitter<String> kafka,
            @ConfigProperty(name = "pix.outbox.lote", defaultValue = "100") int lote,
            @ConfigProperty(name = "pix.outbox.espera-maxima-ms", defaultValue = "10000") long esperaMaximaMs,
            @ConfigProperty(name = "pix.outbox.intervalo-ms", defaultValue = "100") long intervaloMs) {
        this.banco = banco;
        this.kafka = kafka;
        this.lote = lote;
        this.esperaMaximaMs = esperaMaximaMs;
        this.intervaloMs = intervaloMs;
    }

    void iniciar(@Observes StartupEvent subida) {
        relogio = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("publicador-outbox").factory());
        relogio.scheduleWithFixedDelay(this::publicarPendentes, intervaloMs, intervaloMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void parar() {
        if (relogio != null) {
            relogio.shutdownNow();
        }
    }

    private record Pendente(long id, String chave, String tipo, String payload) {}

    void publicarPendentes() {
        try {
            int publicados;
            do {
                publicados = QuarkusTransaction.requiringNew().call(this::publicarUmLote);
            } while (publicados == lote);
        } catch (RuntimeException e) {
            LOG.warnf("Não foi possível publicar os eventos pendentes; nova tentativa na próxima rodada: %s", e.getMessage());
        }
    }

    private int publicarUmLote() throws SQLException {
        try (Connection c = banco.getConnection()) {
            List<Pendente> pendentes = new ArrayList<>();
            try (PreparedStatement sql = c.prepareStatement("""
                    SELECT id, chave, tipo, payload::text AS payload
                      FROM outbox
                     WHERE publicado_em IS NULL
                     ORDER BY id
                     LIMIT ?
                       FOR UPDATE SKIP LOCKED
                    """)) {
                sql.setInt(1, lote);
                try (ResultSet rs = sql.executeQuery()) {
                    while (rs.next()) {
                        pendentes.add(new Pendente(rs.getLong("id"), rs.getString("chave"), rs.getString("tipo"), rs.getString("payload")));
                    }
                }
            }
            if (pendentes.isEmpty()) {
                return 0;
            }
            List<CompletableFuture<Void>> envios = new ArrayList<>(pendentes.size());
            for (Pendente p : pendentes) {
                envios.add(enviar(p));
            }
            esperarConfirmacao(envios);
            try (PreparedStatement sql = c.prepareStatement("UPDATE outbox SET publicado_em = now() WHERE id = ANY (?)")) {
                sql.setArray(1, c.createArrayOf("bigint", pendentes.stream().map(Pendente::id).toArray()));
                sql.executeUpdate();
            }
            return pendentes.size();
        }
    }

    private CompletableFuture<Void> enviar(Pendente p) {
        var confirmacao = new CompletableFuture<Void>();
        var cabecalhos = new RecordHeaders().add("tipo", p.tipo().getBytes(StandardCharsets.UTF_8));
        var metadados = OutgoingKafkaRecordMetadata.<String>builder()
                .withKey(p.chave())
                .withHeaders(cabecalhos)
                .build();
        kafka.send(Message.of(p.payload(), Metadata.of(metadados))
                .withAck(() -> {
                    confirmacao.complete(null);
                    return CompletableFuture.completedFuture(null);
                })
                .withNack(erro -> {
                    confirmacao.completeExceptionally(erro);
                    return CompletableFuture.completedFuture(null);
                }));
        return confirmacao;
    }

    private void esperarConfirmacao(List<CompletableFuture<Void>> envios) {
        try {
            CompletableFuture.allOf(envios.toArray(CompletableFuture[]::new)).get(esperaMaximaMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Publicação interrompida", e);
        } catch (Exception e) {
            throw new IllegalStateException("O Kafka não confirmou o lote: " + e.getMessage(), e);
        }
    }

    /** Faxina: eventos publicados há mais de 7 dias já foram consumidos e podem sair da tabela. */
    @Scheduled(every = "1h", delayed = "1m")
    void apagarPublicadosAntigos() {
        QuarkusTransaction.requiringNew().run(() -> {
            try (Connection c = banco.getConnection();
                    PreparedStatement sql = c.prepareStatement(
                            "DELETE FROM outbox WHERE publicado_em < now() - INTERVAL '7 days'")) {
                int apagados = sql.executeUpdate();
                if (apagados > 0) {
                    LOG.infof("Faxina do Outbox: %d eventos publicados há mais de 7 dias apagados", apagados);
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
