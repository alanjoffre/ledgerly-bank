package io.github.alanjoffre.ledgerly.antifraude.integracao;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * O antifraude de ponta a ponta: Quarkus de verdade, PostgreSQL em contêiner, o modelo ONNX
 * carregado do jar. Quem chama é dito pelo {@code @TestSecurity} (o token de verdade já é testado
 * no pix, que confere o mesmo emissor).
 */
@QuarkusTest
@TestSecurity(user = "ledgerly-pix", roles = "servico")
class AvaliacaoIT {

    // 14:00 em Brasília: nada de madrugada, a não ser que o teste peça.
    private static final Instant TARDE = Instant.parse("2026-10-29T17:00:00Z");

    @Inject
    DataSource banco;

    @BeforeEach
    void zerar() throws SQLException {
        try (Connection c = banco.getConnection(); var sql = c.createStatement()) {
            sql.execute("TRUNCATE avaliacao");
        }
    }

    private static Map<String, Object> pedido(UUID transferencia, UUID pagador, UUID recebedor, String valor,
            Instant instante, Duration idadeDaConta) {
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("transferenciaId", transferencia.toString());
        corpo.put("pagadorContaId", pagador.toString());
        corpo.put("recebedorContaId", recebedor.toString());
        corpo.put("valor", valor);
        corpo.put("instante", instante.toString());
        corpo.put("contaRecebedoraAbertaEm", instante.minus(idadeDaConta).toString());
        return corpo;
    }

    private static ValidatableResponse avaliar(Map<String, Object> corpo) {
        return given().contentType(ContentType.JSON).body(corpo).when().post("/v1/avaliacoes").then();
    }

    /** O costume de um pagador: n Pix de valor parecido para o mesmo recebedor, um por dia. */
    private static void costume(UUID pagador, UUID recebedor, int n) {
        costume(pagador, recebedor, n, "80.00");
    }

    private static void costume(UUID pagador, UUID recebedor, int n, String valor) {
        for (int i = n; i >= 1; i--) {
            avaliar(pedido(UUID.randomUUID(), pagador, recebedor, valor, TARDE.minus(Duration.ofDays(i)),
                    Duration.ofDays(800))).statusCode(200);
        }
    }

    @Test
    void pixDeCostumeEAprovado() {
        UUID pagador = UUID.randomUUID();
        UUID recebedor = UUID.randomUUID();
        costume(pagador, recebedor, 5);
        avaliar(pedido(UUID.randomUUID(), pagador, recebedor, "90.00", TARDE, Duration.ofDays(800)))
                .statusCode(200)
                .body("decisao", equalTo("APROVAR"))
                .body("nota", lessThan(200))
                .body("fatos.razaoDaMedia", equalTo("1.125"))
                .body("fatos.recebedorNovo", equalTo(false))
                .body("modelo", matchesPattern("risco-pix@[0-9a-f]{12}"));
    }

    @Test
    void contaLaranjaESeguradaPelaRegraMesmoComOModeloEmDuvida() {
        // Quem costuma pagar R$ 1.000 manda R$ 1.200 para uma conta aberta ontem: o modelo fica
        // abaixo de 500 (o valor está no costume), e a regra da conta laranja segura.
        UUID pagador = UUID.randomUUID();
        costume(pagador, UUID.randomUUID(), 5, "1000.00");
        avaliar(pedido(UUID.randomUUID(), pagador, UUID.randomUUID(), "1200.00", TARDE, Duration.ofHours(20)))
                .statusCode(200)
                .body("decisao", equalTo("SEGURAR"))
                .body("nota", equalTo(500))
                .body("fatos.razaoDaMedia", equalTo("1.2"))
                .body("fatos.idadeDaContaRecebedoraEmDias", equalTo(0))
                .body("motivos[0].codigo", equalTo("regra:conta-nova-valor-alto"))
                .body("motivos[0].texto", equalTo(
                        "R$ 1.200,00 para uma conta aberta há menos de 1 dia, que nunca recebeu deste pagador"));
    }

    @Test
    void notaMuitoAltaERecusada() {
        UUID pagador = UUID.randomUUID();
        costume(pagador, UUID.randomUUID(), 3);
        UUID laranja = UUID.randomUUID();
        // A rajada: quatro tentativas em minutos, de madrugada, para a mesma conta recém-aberta.
        Instant madrugada = Instant.parse("2026-10-29T06:10:00Z"); // 03:10 em Brasília
        for (int i = 0; i < 4; i++) {
            avaliar(pedido(UUID.randomUUID(), pagador, laranja, "3000.00", madrugada.plusSeconds(60L * i),
                    Duration.ofHours(3)));
        }
        avaliar(pedido(UUID.randomUUID(), pagador, UUID.randomUUID(), "9000.00", madrugada.plusSeconds(300),
                Duration.ofHours(3)))
                .statusCode(200)
                .body("decisao", equalTo("RECUSAR"))
                .body("fatos.pixNaUltimaHora", equalTo(4))
                .body("fatos.madrugada", equalTo(true));
    }

    @Test
    void oRecusadoNaoEnsinaOCostume() {
        // Depois de uma tentativa recusada para a conta laranja, o recebedor continua "novo" e a
        // média continua a de antes: o golpe não vira costume.
        UUID pagador = UUID.randomUUID();
        UUID laranja = UUID.randomUUID();
        costume(pagador, UUID.randomUUID(), 3);
        Instant madrugada = Instant.parse("2026-10-29T06:10:00Z");
        for (int i = 0; i < 5; i++) {
            avaliar(pedido(UUID.randomUUID(), pagador, laranja, "9000.00", madrugada.plusSeconds(60L * i),
                    Duration.ofHours(3)));
        }
        avaliar(pedido(UUID.randomUUID(), pagador, laranja, "80.00", madrugada.plus(Duration.ofHours(3)),
                Duration.ofHours(6)))
                .statusCode(200)
                .body("fatos.recebedorNovo", equalTo(true))
                .body("fatos.razaoDaMedia", equalTo("1"));
    }

    @Test
    void primeiroPixNaoTemMedia() {
        avaliar(pedido(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "50.00", TARDE, Duration.ofDays(500)))
                .statusCode(200)
                .body("fatos.razaoDaMedia", nullValue())
                .body("fatos.recebedorNovo", equalTo(true));
    }

    @Test
    void avaliarDeNovoDevolveAMesmaAvaliacao() {
        UUID transferencia = UUID.randomUUID();
        var corpo = pedido(transferencia, UUID.randomUUID(), UUID.randomUUID(), "4800.00", TARDE, Duration.ofHours(20));
        int nota = avaliar(corpo).statusCode(200).extract().path("nota");
        String em = avaliar(corpo).statusCode(200).body("nota", equalTo(nota)).extract().path("avaliadaEm");
        avaliar(corpo).statusCode(200).body("avaliadaEm", equalTo(em));
    }

    @Test
    void doisPedidosIguaisAoMesmoTempoGravamUmaAvaliacaoSo() throws Exception {
        var corpo = pedido(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "300.00", TARDE, Duration.ofDays(40));
        try (var pool = Executors.newFixedThreadPool(8)) {
            var respostas = IntStream.range(0, 8)
                    .mapToObj(i -> CompletableFuture.supplyAsync(
                            () -> avaliar(corpo).statusCode(200).extract().<String>path("avaliadaEm"), pool))
                    .toList();
            var instantes = respostas.stream().map(CompletableFuture::join).distinct().toList();
            assertThat(instantes).hasSize(1);
        }
        try (Connection c = banco.getConnection(); var sql = c.createStatement();
                var rs = sql.executeQuery("SELECT count(*) FROM avaliacao")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void consultarDevolveOQueFoiGravado() {
        UUID transferencia = UUID.randomUUID();
        avaliar(pedido(transferencia, UUID.randomUUID(), UUID.randomUUID(), "4800.00", TARDE, Duration.ofHours(20)))
                .statusCode(200);
        given().when().get("/v1/avaliacoes/{id}", transferencia).then()
                .statusCode(200)
                .body("transferenciaId", equalTo(transferencia.toString()))
                .body("decisao", equalTo("SEGURAR"))
                .body("motivos.codigo", hasItem("regra:conta-nova-valor-alto"));
    }

    @Test
    void consultarOQueNaoExisteE404() {
        given().when().get("/v1/avaliacoes/{id}", UUID.randomUUID()).then()
                .statusCode(404)
                .contentType("application/problem+json")
                .body("type", equalTo("urn:ledgerly:erro:avaliacao-nao-encontrada"));
    }

    @Test
    void pedidoIncompletoE400ComOsCampos() {
        var corpo = pedido(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "10,50", TARDE, Duration.ofDays(1));
        corpo.remove("recebedorContaId");
        avaliar(corpo)
                .statusCode(400)
                .body("type", equalTo("urn:ledgerly:erro:requisicao-invalida"))
                .body("campos.valor", startsWith("deve ser um valor"))
                .body("campos.recebedorContaId", notNullValue());
    }

    @Test
    void valorZeroE400() {
        avaliar(pedido(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "0.00", TARDE, Duration.ofDays(1)))
                .statusCode(400)
                .body("type", equalTo("urn:ledgerly:erro:requisicao-invalida"));
    }

    @Test
    void jsonInvalidoE400EmProblemDetails() {
        given().contentType(ContentType.JSON).body("{\"transferenciaId\": ").when().post("/v1/avaliacoes").then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("type", equalTo("urn:ledgerly:erro:requisicao-invalida"));
    }

    @Test
    void corpoQueNaoEJsonE415() {
        given().contentType(ContentType.TEXT).body("valor=10.00").when().post("/v1/avaliacoes").then()
                .statusCode(415)
                .contentType("application/problem+json")
                .body("type", equalTo("urn:ledgerly:erro:tipo-de-conteudo-nao-suportado"));
    }

    @Test
    void metodoQueNaoExisteE405() {
        given().when().delete("/v1/avaliacoes/{id}", UUID.randomUUID()).then()
                .statusCode(405)
                .contentType("application/problem+json")
                .body("type", equalTo("urn:ledgerly:erro:metodo-nao-permitido"));
    }

    @Nested
    class Permissoes {

        @Test
        @TestSecurity(user = "ana", roles = "analista")
        void analistaConsultaMasNaoAvalia() {
            avaliar(pedido(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "10.00", TARDE, Duration.ofDays(1)))
                    .statusCode(403)
                    .body("type", equalTo("urn:ledgerly:erro:acesso-negado"));
            given().when().get("/v1/avaliacoes/{id}", UUID.randomUUID()).then().statusCode(404);
        }

        @Test
        @TestSecurity(user = "bia", roles = "cliente")
        void clienteNaoVeNada() {
            given().when().get("/v1/avaliacoes/{id}", UUID.randomUUID()).then().statusCode(403);
        }

        @Test
        @TestSecurity
        void semTokenE401() {
            given().when().get("/v1/avaliacoes/{id}", UUID.randomUUID()).then()
                    .statusCode(401)
                    .header("WWW-Authenticate", startsWith("Bearer"));
        }
    }
}
