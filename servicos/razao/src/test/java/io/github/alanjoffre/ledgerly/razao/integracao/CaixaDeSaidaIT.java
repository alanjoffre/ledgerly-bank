package io.github.alanjoffre.ledgerly.razao.integracao;

import static io.github.alanjoffre.ledgerly.nucleo.Dinheiro.reais;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.alanjoffre.ledgerly.razao.aplicacao.Comandos;
import io.github.alanjoffre.ledgerly.razao.dominio.ContaRazao;
import io.github.alanjoffre.ledgerly.razao.dominio.Lancamento;
import io.github.alanjoffre.ledgerly.razao.dominio.SaldoInsuficienteException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * O Outbox (ADR-0013) de ponta a ponta: o evento é gravado na mesma transação do lançamento e o
 * publicador o entrega no Kafka — inclusive depois de o Kafka ficar fora do ar.
 */
@DisplayName("Outbox do razão: LancamentoRegistrado")
class CaixaDeSaidaIT extends TesteDeIntegracao {

    private static final String TOPICO = "razao.lancamentos.v1";

    @Autowired
    private JsonMapper json;

    @Autowired
    private KafkaConnectionDetails kafkaDetalhes;

    @Autowired
    private KafkaContainer kafka;

    private List<String> payloadsNoOutbox() {
        return jdbc.sql("SELECT payload::text FROM outbox ORDER BY id").query(String.class).list();
    }

    private String servidores() {
        return String.join(",", kafkaDetalhes.getBootstrapServers());
    }

    @Test
    @DisplayName("cada lançamento grava um evento com o envelope e as partidas do contrato")
    void envelope() {
        UUID conta = novaConta();
        Lancamento deposito = lancamentos
                .depositar(new Comandos.Deposito(chave(), conta, reais("150.00"), "salário"))
                .lancamento();

        List<String> payloads = payloadsNoOutbox();
        assertThat(payloads).hasSize(1);
        JsonNode envelope = json.readTree(payloads.getFirst());
        assertThat(envelope.path("tipo").asString()).isEqualTo("LancamentoRegistrado");
        assertThat(envelope.path("versao").asInt()).isEqualTo(1);
        assertThat(UUID.fromString(envelope.path("eventoId").asString())).isNotNull();
        assertThat(envelope.path("ocorridoEm").asString()).isEqualTo(deposito.registradoEm().toString());

        JsonNode dados = envelope.path("dados");
        assertThat(dados.path("lancamentoId").asString()).isEqualTo(deposito.id().toString());
        assertThat(dados.path("tipo").asString()).isEqualTo("DEPOSITO");
        assertThat(dados.path("descricao").asString()).isEqualTo("salário");
        assertThat(dados.path("moeda").asString()).isEqualTo("BRL");

        JsonNode caixa = dados.path("partidas").get(0);
        JsonNode cliente = dados.path("partidas").get(1);
        assertThat(caixa.path("contaId").asString()).isEqualTo(ContaRazao.CAIXA.toString());
        assertThat(caixa.path("contaInterna").asBoolean()).isTrue();
        assertThat(cliente.path("contaId").asString()).isEqualTo(conta.toString());
        assertThat(cliente.path("contaInterna").asBoolean()).isFalse();
        assertThat(cliente.path("tipo").asString()).isEqualTo("CREDITO");
        // Dinheiro como texto, com as casas da moeda (ADR-0012).
        assertThat(cliente.path("valor").isString()).isTrue();
        assertThat(cliente.path("valor").asString()).isEqualTo("150.00");
        assertThat(cliente.path("saldoApos").asString()).isEqualTo("150.00");
        assertThat(cliente.path("sequencia").asLong()).isGreaterThan(caixa.path("sequencia").asLong());
    }

    @Test
    @DisplayName("pedido recusado ou repetido não grava evento: só fato que aconteceu vira evento")
    void soFatosViramEvento() {
        UUID conta = novaContaCom("10.00");
        var saque = new Comandos.Saque(chave(), conta, reais("5.00"), null);
        lancamentos.sacar(saque);
        lancamentos.sacar(saque); // repetido: mesma chave

        assertThatThrownBy(() -> lancamentos.sacar(new Comandos.Saque(chave(), conta, reais("999.00"), null)))
                .isInstanceOf(SaldoInsuficienteException.class);

        assertThat(payloadsNoOutbox()).hasSize(2); // o depósito inicial e o primeiro saque
    }

    @Test
    @DisplayName("o publicador entrega no Kafka, com a chave e o cabeçalho, e marca como publicado")
    void publicaNoKafka() {
        UUID conta = novaConta();
        UUID lancamentoId = lancamentos
                .depositar(new Comandos.Deposito(chave(), conta, reais("42.00"), null))
                .lancamento()
                .id();

        List<ConsumerRecord<String, String>> recebidas = LeitorDeTopico.esperar(
                servidores(), TOPICO, r -> lancamentoId.toString().equals(r.key()), 1, Duration.ofSeconds(30));

        assertThat(recebidas).hasSize(1);
        ConsumerRecord<String, String> mensagem = recebidas.getFirst();
        assertThat(new String(mensagem.headers().lastHeader("tipo").value(), StandardCharsets.UTF_8))
                .isEqualTo("LancamentoRegistrado");
        assertThat(json.readTree(mensagem.value()).path("dados").path("lancamentoId").asString())
                .isEqualTo(lancamentoId.toString());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(pendentes()).isZero());
    }

    @Test
    @DisplayName("Kafka fora do ar: o depósito funciona, o evento espera na tabela e sai quando ele volta")
    void kafkaForaDoAr() {
        UUID conta = novaConta();
        var docker = kafka.getDockerClient();
        docker.pauseContainerCmd(kafka.getContainerId()).exec();
        UUID lancamentoId;
        try {
            lancamentoId = lancamentos
                    .depositar(new Comandos.Deposito(chave(), conta, reais("7.00"), null))
                    .lancamento()
                    .id();
            assertThat(saldo(conta)).isEqualTo(reais("7.00"));
            // Com o Kafka parado, o evento continua pendente — não se perde nem trava o depósito.
            await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4))
                    .untilAsserted(() -> assertThat(pendentes()).isEqualTo(1));
        } finally {
            docker.unpauseContainerCmd(kafka.getContainerId()).exec();
        }

        List<ConsumerRecord<String, String>> recebidas = LeitorDeTopico.esperar(
                servidores(), TOPICO, r -> lancamentoId.toString().equals(r.key()), 1, Duration.ofSeconds(60));
        assertThat(recebidas).hasSize(1);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(pendentes()).isZero());
    }

    private long pendentes() {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE publicado_em IS NULL").query(Long.class).single();
    }
}
