package io.github.alanjoffre.ledgerly.razao.adaptadores.saida.eventos;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O publicador do Outbox (ADR-0013): lê os eventos pendentes da tabela {@code outbox} e manda ao
 * Kafka.
 *
 * <p>A cada rodada, numa transação: pega um lote de pendentes ({@code FOR UPDATE SKIP LOCKED}),
 * manda todos, <b>espera o Kafka confirmar</b> e só então marca como publicados. Se o Kafka falhar,
 * a transação é desfeita e o lote volta na próxima rodada — nada se perde.
 *
 * <p>A garantia é "pelo menos uma vez": se o serviço cair entre a confirmação do Kafka e o COMMIT,
 * o lote sai de novo. Por isso todo consumidor é idempotente.
 *
 * <p>{@code SKIP LOCKED}: se houver duas instâncias do razão, cada uma pula as linhas que a outra
 * já travou, em vez de esperar — as duas trabalham sem publicar a mesma linha.
 */
@Component
@ConditionalOnProperty(name = "razao.outbox.publicar", havingValue = "true", matchIfMissing = true)
class PublicadorDaCaixaDeSaida {

    private static final Logger LOG = LoggerFactory.getLogger(PublicadorDaCaixaDeSaida.class);

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transacao;
    private final int lote;
    private final long esperaMaximaMs;

    PublicadorDaCaixaDeSaida(
            JdbcClient jdbc,
            KafkaTemplate<String, String> kafka,
            TransactionTemplate transacao,
            @Value("${razao.outbox.lote:100}") int lote,
            @Value("${razao.outbox.espera-maxima-ms:10000}") long esperaMaximaMs) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.transacao = transacao;
        this.lote = lote;
        this.esperaMaximaMs = esperaMaximaMs;
    }

    private record Pendente(long id, String topico, String chave, String tipo, String payload) {}

    @Scheduled(fixedDelayString = "${razao.outbox.intervalo-ms:100}")
    void publicarPendentes() {
        try {
            int publicados;
            do {
                publicados = publicarUmLote();
            } while (publicados == lote); // lote cheio: pode haver mais esperando
        } catch (RuntimeException e) {
            // O Kafka fora do ar não derruba nada: o lote continua pendente e volta na próxima rodada.
            LOG.warn("Não foi possível publicar os eventos pendentes; nova tentativa na próxima rodada: {}", e.getMessage());
        }
    }

    /** Publica um lote e devolve quantos eventos foram publicados. */
    int publicarUmLote() {
        Integer publicados = transacao.execute(status -> {
            List<Pendente> pendentes = jdbc.sql("""
                            SELECT id, topico, chave, tipo, payload::text AS payload
                              FROM outbox
                             WHERE publicado_em IS NULL
                             ORDER BY id
                             LIMIT :lote
                               FOR UPDATE SKIP LOCKED
                            """)
                    .param("lote", lote)
                    .query((rs, n) -> new Pendente(
                            rs.getLong("id"),
                            rs.getString("topico"),
                            rs.getString("chave"),
                            rs.getString("tipo"),
                            rs.getString("payload")))
                    .list();
            if (pendentes.isEmpty()) {
                return 0;
            }
            List<CompletableFuture<?>> envios = new ArrayList<>(pendentes.size());
            for (Pendente p : pendentes) {
                var registro = new ProducerRecord<>(p.topico(), p.chave(), p.payload());
                registro.headers().add("tipo", p.tipo().getBytes(StandardCharsets.UTF_8));
                envios.add(kafka.send(registro));
            }
            esperarConfirmacao(envios);
            jdbc.sql("UPDATE outbox SET publicado_em = now() WHERE id IN (:ids)")
                    .param("ids", pendentes.stream().map(Pendente::id).toList())
                    .update();
            return pendentes.size();
        });
        return publicados == null ? 0 : publicados;
    }

    private void esperarConfirmacao(List<CompletableFuture<?>> envios) {
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
    @Scheduled(fixedDelayString = "${razao.outbox.faxina-ms:3600000}")
    void apagarPublicadosAntigos() {
        int apagados = jdbc.sql("DELETE FROM outbox WHERE publicado_em < now() - INTERVAL '7 days'").update();
        if (apagados > 0) {
            LOG.info("Faxina do Outbox: {} eventos publicados há mais de 7 dias apagados", apagados);
        }
    }
}
