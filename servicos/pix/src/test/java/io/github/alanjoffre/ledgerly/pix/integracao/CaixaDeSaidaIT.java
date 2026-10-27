package io.github.alanjoffre.ledgerly.pix.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** O Outbox do Pix: PixConcluido e PixEstornado chegam ao Kafka no formato do contrato. */
@QuarkusTest
@WithTestResource(ServicosFalsos.class)
@DisplayName("Outbox do Pix: eventos no Kafka")
class CaixaDeSaidaIT extends TesteDeIntegracao {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ConsumerRecord<String, String> esperarMensagem(String chave) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String.class),
                ConsumerConfig.GROUP_ID_CONFIG, "teste-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> achadas = new ArrayList<>();
        try (var consumidor = new KafkaConsumer<String, String>(config)) {
            consumidor.subscribe(List.of("pix.transferencias.v1"));
            Instant limite = Instant.now().plusSeconds(30);
            while (achadas.isEmpty() && Instant.now().isBefore(limite)) {
                consumidor.poll(Duration.ofMillis(200)).forEach(r -> {
                    if (chave.equals(r.key())) {
                        achadas.add(r);
                    }
                });
            }
        }
        assertThat(achadas).as("mensagem com a chave %s no Kafka", chave).hasSize(1);
        return achadas.getFirst();
    }

    @Test
    @DisplayName("PixConcluido: chave = pixId, cabeçalho tipo, nomes, chave mascarada e os dois lançamentos")
    void concluido() throws Exception {
        Conta joao = novaConta("João da Silva", "100.00");
        Conta maria = novaConta("Maria Souza", "0.00");
        String email = chaveEmail(maria);
        String id = pix(chave(), joao.id(), email, "12.34").statusCode(201).extract().path("id");

        ConsumerRecord<String, String> mensagem = esperarMensagem(id);
        assertThat(new String(mensagem.headers().lastHeader("tipo").value(), StandardCharsets.UTF_8)).isEqualTo("PixConcluido");
        JsonNode envelope = JSON.readTree(mensagem.value());
        assertThat(envelope.path("tipo").asText()).isEqualTo("PixConcluido");
        assertThat(envelope.path("versao").asInt()).isEqualTo(1);
        JsonNode dados = envelope.path("dados");
        assertThat(dados.path("pixId").asText()).isEqualTo(id);
        assertThat(dados.path("valor").asText()).isEqualTo("12.34");
        assertThat(dados.path("pagador").path("nome").asText()).isEqualTo("João da Silva");
        assertThat(dados.path("pagador").path("contaId").asText()).isEqualTo(joao.id().toString());
        assertThat(dados.path("recebedor").path("nome").asText()).isEqualTo("Maria Souza");
        assertThat(dados.path("recebedor").path("tipoChave").asText()).isEqualTo("EMAIL");
        assertThat(dados.path("recebedor").path("chave").asText()).startsWith(email.charAt(0) + "***@");
        assertThat(dados.path("lancamentoEnvioId").asText()).isNotBlank();
        assertThat(dados.path("lancamentoRecebimentoId").asText()).isNotBlank();
        assertThat(dados.path("concluidoEm").asText()).isNotBlank();
    }

    @Test
    @DisplayName("PixEstornado: o motivo e o lançamento do estorno")
    void estornado() throws Exception {
        Conta joao = novaConta("João", "100.00");
        Conta maria = novaConta("Maria", "0.00");
        String email = chaveEmail(maria);
        ServicosFalsos.RAZAO.encerrar(maria.id());
        String id = pix(chave(), joao.id(), email, "5.00").statusCode(201).extract().path("id");

        JsonNode dados = JSON.readTree(esperarMensagem(id).value()).path("dados");
        assertThat(dados.path("motivo").asText()).contains("encerrada");
        assertThat(dados.path("lancamentoEstornoId").asText()).isNotBlank();
        assertThat(dados.has("lancamentoRecebimentoId")).isFalse();
    }
}
