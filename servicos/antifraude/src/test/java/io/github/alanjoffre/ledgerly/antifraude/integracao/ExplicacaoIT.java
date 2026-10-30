package io.github.alanjoffre.ledgerly.antifraude.integracao;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.alanjoffre.ledgerly.antifraude.aplicacao.AvaliacaoService;
import io.github.alanjoffre.ledgerly.antifraude.dominio.Decisao;
import io.github.alanjoffre.ledgerly.antifraude.dominio.PedidoDeAvaliacao;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A explicação para a analista (ADR-0035), com o Quarkus e o PostgreSQL de verdade e um Ollama falso.
 */
@QuarkusTest
@WithTestResource(OllamaFalso.class)
@TestSecurity(user = "carla", roles = "analista")
@DisplayName("Explicação do alerta")
class ExplicacaoIT {

    private static final Instant TARDE = Instant.parse("2026-10-29T17:00:00Z");
    private static final OllamaFalso.Estado OLLAMA = OllamaFalso.ESTADO;

    @Inject
    DataSource banco;

    @Inject
    AvaliacaoService avaliacoes;

    @BeforeEach
    void zerar() throws SQLException {
        OLLAMA.zerar();
        try (Connection c = banco.getConnection(); var sql = c.createStatement()) {
            sql.execute("TRUNCATE avaliacao");
        }
    }

    /** Avalia um Pix pelo serviço (o POST, com o papel do pix, já é testado no AvaliacaoIT). */
    private String avaliar(String valor, Instant contaAbertaEm, Decisao esperada) {
        UUID id = UUID.randomUUID();
        var avaliacao = avaliacoes.avaliar(new PedidoDeAvaliacao(
                id, UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(valor), TARDE, contaAbertaEm));
        assertThat(avaliacao.decisao()).isEqualTo(esperada);
        return id.toString();
    }

    /** Um Pix de R$ 1.200,00 para uma conta aberta agora: a regra da conta "laranja" segura (nota 500). */
    private String laranja() {
        return avaliar("1200.00", TARDE, Decisao.SEGURAR);
    }

    private static ValidatableResponse explicar(String id) {
        return given().when().get("/v1/avaliacoes/{id}/explicacao", id).then();
    }

    @Test
    @DisplayName("o modelo de linguagem escreve com os números da avaliação: vale o texto dele, gravado")
    void textoDoModelo() {
        String id = laranja();
        String texto = "O Pix de R$ 1.200,00 vai para uma conta aberta há menos de 1 dia, que nunca recebeu deste pagador. "
                + "É o desenho de uma conta laranja, e por isso a nota foi a 500.";
        OLLAMA.responder(texto);

        explicar(id).statusCode(200)
                .body("texto", equalTo(texto))
                .body("origem", equalTo("MODELO_DE_LINGUAGEM"))
                .body("modelo", equalTo("modelo-falso:1b"));

        // A segunda leitura não chama o modelo de novo: o texto está gravado.
        OLLAMA.responder("outro texto");
        explicar(id).statusCode(200).body("texto", equalTo(texto));
        assertThat(OLLAMA.pedidos()).hasSize(1);
    }

    @Test
    @DisplayName("o pedido ao Ollama: o modelo configurado, temperatura baixa, as instruções e os fatos — sem id de conta")
    void pedido() {
        String id = laranja();
        OLLAMA.responder("Conta recém-aberta e valor alto para um recebedor novo.");
        explicar(id).statusCode(200);

        JsonNode pedido = OLLAMA.pedidos().getFirst();
        assertThat(pedido.path("model").asText()).isEqualTo("modelo-falso:1b");
        assertThat(pedido.path("stream").asBoolean()).isFalse();
        assertThat(pedido.path("options").path("temperature").asDouble()).isEqualTo(0.2);
        JsonNode mensagens = pedido.path("messages");
        assertThat(mensagens.get(0).path("role").asText()).isEqualTo("system");
        assertThat(mensagens.get(0).path("content").asText()).contains("não invente");
        String fatos = mensagens.get(1).path("content").asText();
        assertThat(fatos).contains("segurado para análise (nota 500 de 1000)").contains("R$ 1.200,00").doesNotContain(id);
    }

    @Test
    @DisplayName("o modelo inventou um número: o texto cai, e o texto fixo é gravado no lugar")
    void numeroInventado() {
        String id = laranja();
        OLLAMA.responder("O Pix de R$ 12.000,00 é dez vezes maior que o normal.");

        explicar(id).statusCode(200)
                .body("origem", equalTo("TEXTO_FIXO"))
                .body("modelo", nullValue())
                .body("texto", startsWith("Nota 500 de 1000: o Pix foi retido para uma pessoa analisar. Pesou: R$ 1.200,00"));
        explicar(id).statusCode(200).body("origem", equalTo("TEXTO_FIXO"));
        assertThat(OLLAMA.pedidos()).hasSize(1);
    }

    @Test
    @DisplayName("Ollama fora do ar: texto fixo na resposta, mas não gravado — a próxima leitura tenta de novo")
    void foraDoAr() {
        String id = laranja();
        OLLAMA.foraDoAr();
        explicar(id).statusCode(200).body("origem", equalTo("TEXTO_FIXO"));

        OLLAMA.responder("Conta recém-aberta, recebedor novo e valor alto.");
        explicar(id).statusCode(200).body("origem", equalTo("MODELO_DE_LINGUAGEM"));
        assertThat(OLLAMA.pedidos()).hasSize(2);
    }

    @Test
    @DisplayName("Pix aprovado: texto fixo, sem chamar o modelo de linguagem")
    void aprovado() {
        String id = avaliar("35.00", Instant.parse("2024-01-01T12:00:00Z"), Decisao.APROVAR);

        explicar(id).statusCode(200)
                .body("origem", equalTo("TEXTO_FIXO"))
                .body("texto", startsWith("Nota "));
        assertThat(OLLAMA.pedidos()).isEmpty();
    }

    @Test
    @DisplayName("duas analistas abrem o mesmo Pix ao mesmo tempo: as duas leem o mesmo texto")
    void aoMesmoTempo() throws Exception {
        String id = laranja();
        OLLAMA.responder("Valor alto para uma conta aberta há menos de 1 dia.");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var leituras = IntStream.range(0, 6)
                    .mapToObj(i -> CompletableFuture.supplyAsync(
                            () -> explicar(id).statusCode(200).extract().<String>path("explicadaEm"), executor))
                    .toList();
            assertThat(leituras.stream().map(CompletableFuture::join).distinct()).hasSize(1);
        }
    }

    @Test
    @DisplayName("transferência sem avaliação: 404")
    void naoEncontrada() {
        explicar(UUID.randomUUID().toString()).statusCode(404)
                .body("type", equalTo("urn:ledgerly:erro:avaliacao-nao-encontrada"));
    }

    @Test
    @DisplayName("cliente não lê a explicação: 403")
    @TestSecurity(user = "ana", roles = "cliente")
    void cliente() {
        explicar(UUID.randomUUID().toString()).statusCode(403);
    }
}
