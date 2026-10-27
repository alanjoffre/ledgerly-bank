package io.github.alanjoffre.ledgerly.razao.integracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.alanjoffre.ledgerly.razao.dominio.ContaRazao;
import io.github.alanjoffre.ledgerly.razao.dominio.SituacaoConta;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.kafka.core.KafkaTemplate;

/** O razão consumindo {@code contas.eventos.v1} (ADR-0014): idempotente, em ordem, com DLT. */
@DisplayName("Consumidor dos eventos de conta")
class ConsumidorDeEventosDeContaIT extends TesteDeIntegracao {

    private static final String TOPICO = "contas.eventos.v1";
    private static final Duration PRAZO = Duration.ofSeconds(30);

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private KafkaConnectionDetails kafkaDetalhes;

    private void publicar(UUID contaId, String tipo, String dados) {
        String envelope = """
                {"eventoId":"%s","tipo":"%s","versao":1,"ocorridoEm":"2026-10-27T12:00:00Z","dados":%s}"""
                .formatted(UUID.randomUUID(), tipo, dados);
        kafka.send(TOPICO, contaId.toString(), envelope).join();
    }

    private void publicar(UUID contaId, String tipo) {
        publicar(contaId, tipo, "{\"contaId\":\"%s\"}".formatted(contaId));
    }

    private void abrir(UUID contaId) {
        publicar(contaId, "ContaAberta",
                "{\"contaId\":\"%s\",\"clienteId\":\"%s\",\"moeda\":\"BRL\"}".formatted(contaId, UUID.randomUUID()));
    }

    private Optional<SituacaoConta> situacao(UUID contaId) {
        return jdbc.sql("SELECT situacao FROM conta_razao WHERE id = :id")
                .param("id", contaId)
                .query(String.class)
                .optional()
                .map(SituacaoConta::valueOf);
    }

    private void esperarSituacao(UUID contaId, SituacaoConta esperada) {
        await().atMost(PRAZO).untilAsserted(() -> assertThat(situacao(contaId)).contains(esperada));
    }

    @Test
    @DisplayName("ContaAberta abre a conta; repetido, não faz nada")
    void abertura() {
        UUID conta = UUID.randomUUID();
        abrir(conta);
        abrir(conta);
        esperarSituacao(conta, SituacaoConta.ATIVA);
        ContaRazao aberta = contas.consultar(conta);
        assertThat(aberta.moeda().getCurrencyCode()).isEqualTo("BRL");
        assertThat(aberta.saldo().ehZero()).isTrue();
    }

    @Test
    @DisplayName("bloqueio e desbloqueio chegam na ordem em que aconteceram")
    void bloqueioEDesbloqueio() {
        UUID conta = UUID.randomUUID();
        abrir(conta);
        publicar(conta, "ContaBloqueada");
        esperarSituacao(conta, SituacaoConta.BLOQUEADA);
        publicar(conta, "ContaDesbloqueada");
        esperarSituacao(conta, SituacaoConta.ATIVA);
    }

    @Test
    @DisplayName("evento atrasado para uma conta já encerrada é descartado, e a fila segue")
    void contaEncerrada() {
        UUID conta = UUID.randomUUID();
        abrir(conta);
        esperarSituacao(conta, SituacaoConta.ATIVA);
        contas.alterarSituacao(conta, SituacaoConta.ENCERRADA); // o encerramento é síncrono

        publicar(conta, "ContaDesbloqueada"); // ultrapassado pelo encerramento
        UUID outra = UUID.randomUUID();
        abrir(outra);

        esperarSituacao(outra, SituacaoConta.ATIVA);
        assertThat(situacao(conta)).contains(SituacaoConta.ENCERRADA);
    }

    @Test
    @DisplayName("tipo desconhecido é ignorado (o produtor pode criar eventos novos sem quebrar o razão)")
    void tipoDesconhecido() {
        UUID conta = UUID.randomUUID();
        publicar(conta, "ContaRenomeada");
        abrir(conta);
        esperarSituacao(conta, SituacaoConta.ATIVA);
    }

    @Test
    @DisplayName("mensagem venenosa vai para o DLT e não trava as que vêm atrás")
    void mensagemVenenosa() {
        UUID venenosa = UUID.randomUUID();
        kafka.send(TOPICO, venenosa.toString(), "isto não é JSON").join();
        UUID depois = UUID.randomUUID();
        abrir(depois);

        esperarSituacao(depois, SituacaoConta.ATIVA);
        List<ConsumerRecord<String, String>> noDlt = LeitorDeTopico.esperar(
                String.join(",", kafkaDetalhes.getBootstrapServers()),
                TOPICO + ".dlt",
                r -> venenosa.toString().equals(r.key()),
                1,
                PRAZO);
        assertThat(noDlt).hasSize(1);
        assertThat(noDlt.getFirst().value()).isEqualTo("isto não é JSON");
    }
}
