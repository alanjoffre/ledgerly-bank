package io.github.alanjoffre.ledgerly.contas.adaptadores.saida.eventos;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publicador do Outbox (ADR-0013): de tempos em tempos, lê os eventos pendentes da tabela
 * {@code outbox} e os entrega ao Kafka.
 *
 * <p>Cada rodada, numa transação:
 * <ol>
 *   <li>pega um lote de pendentes, na ordem em que foram gravados, com
 *       {@code FOR UPDATE SKIP LOCKED} — duas instâncias do serviço não pegam a mesma linha;</li>
 *   <li>manda todos e <b>espera a confirmação</b> do Kafka ({@code acks=all}: gravado em todas as
 *       réplicas);</li>
 *   <li>marca como publicados e confirma (COMMIT).</li>
 * </ol>
 *
 * <p>Kafka fora do ar → exceção no passo 2 → a transação é desfeita, nada é marcado, e o lote volta
 * na próxima rodada. Se o serviço cair entre o passo 2 e o 3, o lote é mandado de novo: a entrega é
 * <b>pelo menos uma vez</b>, e quem consome descarta a repetição pelo {@code eventoId}.
 */
@Component
class PublicadorDoOutbox {

    private static final Logger LOG = LoggerFactory.getLogger(PublicadorDoOutbox.class);

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transacao;
    private final Clock relogio;
    private final int lote;
    private final Duration tempoLimiteEnvio;
    private final Duration retencao;

    PublicadorDoOutbox(
            JdbcClient jdbc,
            KafkaTemplate<String, String> kafka,
            TransactionTemplate transacao,
            Clock relogio,
            @Value("${ledgerly.outbox.lote:100}") int lote,
            @Value("${ledgerly.outbox.tempo-limite-envio:15s}") Duration tempoLimiteEnvio,
            @Value("${ledgerly.outbox.retencao:7d}") Duration retencao) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.transacao = transacao;
        this.relogio = relogio;
        this.lote = lote;
        this.tempoLimiteEnvio = tempoLimiteEnvio;
        this.retencao = retencao;
    }

    record Pendente(long id, String topico, String chave, String tipo, String payload) {}

    /**
     * Uma rodada: publica lotes até esvaziar a fila. {@code fixedDelay} conta o intervalo a partir do
     * FIM da rodada anterior — duas rodadas nunca se sobrepõem nesta instância.
     */
    @Scheduled(fixedDelayString = "${ledgerly.outbox.intervalo-ms:100}")
    void publicarPendentes() {
        try {
            int publicados;
            do {
                publicados = publicarLote();
            } while (publicados == lote);
        } catch (RuntimeException e) {
            LOG.warn("Outbox: eventos não publicados nesta rodada, nova tentativa na próxima: {}", e.getMessage());
        }
    }

    /** @return quantos eventos foram publicados (0 = nada pendente) */
    int publicarLote() {
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
                    .query(Pendente.class)
                    .list();
            if (pendentes.isEmpty()) {
                return 0;
            }
            esperarConfirmacao(pendentes.stream().map(this::enviar).toArray(CompletableFuture<?>[]::new));
            jdbc.sql("UPDATE outbox SET publicado_em = now() WHERE id IN (:ids)")
                    .param("ids", pendentes.stream().map(Pendente::id).toList())
                    .update();
            return pendentes.size();
        });
        return publicados == null ? 0 : publicados;
    }

    /** Apaga os eventos já publicados há mais tempo que a retenção (padrão: 7 dias). */
    @Scheduled(fixedDelayString = "${ledgerly.outbox.intervalo-limpeza-ms:3600000}", initialDelay = 60_000)
    int apagarPublicadosAntigos() {
        int apagados = jdbc.sql("DELETE FROM outbox WHERE publicado_em < :limite")
                .param("limite", OffsetDateTime.ofInstant(relogio.instant().minus(retencao), ZoneOffset.UTC))
                .update();
        if (apagados > 0) {
            LOG.info("Outbox: {} eventos publicados há mais de {} apagados", apagados, retencao);
        }
        return apagados;
    }

    private CompletableFuture<?> enviar(Pendente pendente) {
        ProducerRecord<String, String> mensagem =
                new ProducerRecord<>(pendente.topico(), pendente.chave(), pendente.payload());
        mensagem.headers().add("tipo", pendente.tipo().getBytes(StandardCharsets.UTF_8));
        return kafka.send(mensagem);
    }

    private void esperarConfirmacao(CompletableFuture<?>[] envios) {
        try {
            CompletableFuture.allOf(envios).get(tempoLimiteEnvio.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Publicação do outbox interrompida", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("O Kafka recusou ou não confirmou o envio: " + e.getCause().getMessage(), e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("O Kafka não confirmou o envio em " + tempoLimiteEnvio, e);
        }
    }
}
