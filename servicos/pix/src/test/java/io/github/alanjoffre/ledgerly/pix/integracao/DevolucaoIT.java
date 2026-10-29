package io.github.alanjoffre.ledgerly.pix.integracao;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import io.github.alanjoffre.ledgerly.pix.integracao.ServicosFalsos.RazaoFalso;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A devolução de um Pix em análise (ADR-0030): o crédito foi recusado, o estorno também (o pagador
 * encerrou a conta no meio), e o dinheiro ficou na Liquidação Pix. O analista do backoffice decide
 * devolvê-lo para outra conta do mesmo titular — e a decisão fica na trilha de auditoria.
 */
@QuarkusTest
@WithTestResource(ServicosFalsos.class)
@DisplayName("Devolução de Pix em análise")
class DevolucaoIT extends TesteDeIntegracao {

    private static final RazaoFalso RAZAO = ServicosFalsos.RAZAO;
    private static final String JUSTIFICATIVA = "Conta de origem encerrada; o cliente pediu a devolução na poupança.";

    /** Um Pix de R$ 100 que termina EM_ANALISE: recebedor e pagador encerrados no razão. */
    private record EmAnalise(String id, Conta pagador, Conta recebedor) {}

    private EmAnalise pixEmAnalise() {
        Conta joao = novaConta("João", "300.00");
        Conta maria = novaConta("Maria", "0.00");
        String chave = chaveEmail(maria);
        RAZAO.encerrar(maria.id());
        RAZAO.encerrar(joao.id());
        String id = pix(chave(), joao.id(), chave, "100.00")
                .statusCode(201)
                .body("situacao", equalTo("EM_ANALISE"))
                .extract().path("id");
        ServicosFalsos.CONTAS.encerrar(joao.id());
        return new EmAnalise(id, joao, maria);
    }

    private static ValidatableResponse devolver(String id, UUID destino, String justificativa) {
        return given().header("Authorization", TokensPix.token().nome("carla").sujeito("id-da-carla").papeis("analista").bearer())
                .contentType(ContentType.JSON)
                .body(Map.of("contaDestinoId", destino.toString(), "justificativa", justificativa))
                .when().post("/v1/transferencias/{id}/devolucao", id)
                .then();
    }

    @Test
    @DisplayName("devolve para a poupança do mesmo titular: ESTORNADA, o dinheiro sai da Liquidação, e a decisão fica gravada")
    void devolve() throws Exception {
        EmAnalise pix = pixEmAnalise();
        Conta poupanca = novaConta("João", "0.00", pix.pagador().clienteId());
        assertThat(RAZAO.liquidacao()).isEqualByComparingTo("100.00");

        given().header("Authorization", TokensPix.analista())
                .when().get("/v1/transferencias?situacao=EM_ANALISE")
                .then().statusCode(200).body("id", hasItem(pix.id()));

        devolver(pix.id(), poupanca.id(), JUSTIFICATIVA)
                .statusCode(200)
                .body("situacao", equalTo("ESTORNADA"))
                .body("contaDevolucaoId", equalTo(poupanca.id().toString()))
                .body("lancamentos.estorno", notNullValue());

        assertThat(RAZAO.saldo(poupanca.id())).isEqualByComparingTo("100.00");
        assertThat(RAZAO.liquidacao()).isEqualByComparingTo("0");
        assertThat(RAZAO.chamadas("pix:" + pix.id() + ":devolucao:" + poupanca.id())).isEqualTo(1);

        try (Connection c = banco.getConnection();
                PreparedStatement sql = c.prepareStatement(
                        "SELECT analista_id, analista_nome, decisao, conta_destino_id, justificativa FROM decisao_de_analise WHERE transferencia_id = ?")) {
            sql.setObject(1, UUID.fromString(pix.id()));
            try (ResultSet rs = sql.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("analista_id")).isEqualTo("id-da-carla");
                assertThat(rs.getString("analista_nome")).isEqualTo("carla");
                assertThat(rs.getString("decisao")).isEqualTo("DEVOLVER");
                assertThat(rs.getObject("conta_destino_id", UUID.class)).isEqualTo(poupanca.id());
                assertThat(rs.getString("justificativa")).isEqualTo(JUSTIFICATIVA);
                assertThat(rs.next()).isFalse();
            }
        }
        // O evento leva a conta da volta: o extrato lança o estorno na poupança, não na conta encerrada.
        try (Connection c = banco.getConnection();
                PreparedStatement sql = c.prepareStatement(
                        "SELECT payload->'dados'->>'contaDevolucaoId' FROM outbox WHERE tipo = 'PixEstornado' AND chave = ?")) {
            sql.setString(1, pix.id());
            try (ResultSet rs = sql.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo(poupanca.id().toString());
            }
        }
        // Saiu da fila do backoffice.
        given().header("Authorization", TokensPix.analista())
                .when().get("/v1/transferencias?situacao=EM_ANALISE")
                .then().statusCode(200).body("id", not(hasItem(pix.id())));
    }

    @Test
    @DisplayName("a segunda decisão para o mesmo Pix: 409, e o dinheiro não sai duas vezes")
    void decisaoRepetida() {
        EmAnalise pix = pixEmAnalise();
        Conta poupanca = novaConta("João", "0.00", pix.pagador().clienteId());
        devolver(pix.id(), poupanca.id(), JUSTIFICATIVA).statusCode(200);

        devolver(pix.id(), poupanca.id(), JUSTIFICATIVA)
                .statusCode(409)
                .body("type", equalTo("urn:ledgerly:erro:transferencia-fora-de-analise"));
        assertThat(RAZAO.saldo(poupanca.id())).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("dois analistas ao mesmo tempo: uma devolução só, uma linha de auditoria só")
    void doisAnalistasAoMesmoTempo() throws Exception {
        EmAnalise pix = pixEmAnalise();
        Conta poupanca = novaConta("João", "0.00", pix.pagador().clienteId());
        Conta corrente = novaConta("João", "0.00", pix.pagador().clienteId());
        var largada = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Integer> a = executor.submit(() -> {
                largada.await();
                return devolver(pix.id(), poupanca.id(), JUSTIFICATIVA).extract().statusCode();
            });
            Future<Integer> b = executor.submit(() -> {
                largada.await();
                return devolver(pix.id(), corrente.id(), JUSTIFICATIVA).extract().statusCode();
            });
            largada.countDown();
            assertThat(java.util.List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(RAZAO.saldo(poupanca.id()).add(RAZAO.saldo(corrente.id()))).isEqualByComparingTo("100.00");
        assertThat(RAZAO.liquidacao()).isEqualByComparingTo("0");
        try (Connection c = banco.getConnection();
                var sql = c.createStatement();
                ResultSet rs = sql.executeQuery("SELECT count(*) FROM decisao_de_analise")) {
            rs.next();
            assertThat(rs.getLong(1)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Pix que não está em análise (concluído): 409")
    void foraDeAnalise() {
        Conta joao = novaConta("João", "300.00");
        Conta maria = novaConta("Maria", "0.00");
        String id = pix(chave(), joao.id(), chaveEmail(maria), "10.00")
                .statusCode(201).body("situacao", equalTo("CONCLUIDA")).extract().path("id");
        Conta poupanca = novaConta("João", "0.00", joao.clienteId());

        devolver(id, poupanca.id(), JUSTIFICATIVA)
                .statusCode(409)
                .body("type", equalTo("urn:ledgerly:erro:transferencia-fora-de-analise"));
    }

    @Test
    @DisplayName("conta de outro titular: 422, e o Pix continua em análise")
    void contaDeOutroTitular() {
        EmAnalise pix = pixEmAnalise();
        Conta deOutro = novaConta("Pedro", "0.00");

        devolver(pix.id(), deOutro.id(), JUSTIFICATIVA)
                .statusCode(422)
                .body("type", equalTo("urn:ledgerly:erro:conta-de-outro-titular"));
        consultar(pix.id()).body("situacao", equalTo("EM_ANALISE"));
        assertThat(RAZAO.liquidacao()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("conta de destino bloqueada: 422 conta-inativa")
    void contaInativa() {
        EmAnalise pix = pixEmAnalise();
        Conta poupanca = novaConta("João", "0.00", pix.pagador().clienteId());
        ServicosFalsos.CONTAS.bloquear(poupanca.id());

        devolver(pix.id(), poupanca.id(), JUSTIFICATIVA)
                .statusCode(422)
                .body("type", equalTo("urn:ledgerly:erro:conta-inativa"));
    }

    @Test
    @DisplayName("conta que não existe: 404; a conta que pagou ou a que recebeu: 422")
    void destinosRecusados() {
        EmAnalise pix = pixEmAnalise();
        devolver(pix.id(), UUID.randomUUID(), JUSTIFICATIVA).statusCode(404);
        // A conta que pagou está encerrada: o serviço de contas responde "não ativa".
        devolver(pix.id(), pix.pagador().id(), JUSTIFICATIVA).statusCode(422);
        consultar(pix.id()).body("situacao", equalTo("EM_ANALISE"));
    }

    @Test
    @DisplayName("sem justificativa (ou com menos de 10 letras): 400, nada é decidido")
    void justificativaObrigatoria() {
        EmAnalise pix = pixEmAnalise();
        Conta poupanca = novaConta("João", "0.00", pix.pagador().clienteId());

        devolver(pix.id(), poupanca.id(), "curta").statusCode(400);
        devolver(pix.id(), poupanca.id(), "          ").statusCode(400);
        consultar(pix.id()).body("situacao", equalTo("EM_ANALISE"));
    }

    @Test
    @DisplayName("só o analista decide: o cliente (mesmo o dono) recebe 403; sem token, 401")
    void soOAnalista() {
        EmAnalise pix = pixEmAnalise();
        Conta poupanca = novaConta("João", "0.00", pix.pagador().clienteId());
        Map<String, String> corpo = Map.of("contaDestinoId", poupanca.id().toString(), "justificativa", JUSTIFICATIVA);

        given().header("Authorization", TokensPix.cliente(pix.pagador().clienteId()))
                .contentType(ContentType.JSON).body(corpo)
                .when().post("/v1/transferencias/{id}/devolucao", pix.id())
                .then().statusCode(403).body("type", equalTo("urn:ledgerly:erro:acesso-negado"));
        given().header("Authorization", TokensPix.servico())
                .contentType(ContentType.JSON).body(corpo)
                .when().post("/v1/transferencias/{id}/devolucao", pix.id())
                .then().statusCode(403);
        io.restassured.RestAssured.requestSpecification = null;
        given().contentType(ContentType.JSON).body(corpo)
                .when().post("/v1/transferencias/{id}/devolucao", pix.id())
                .then().statusCode(401);
        consultar(pix.id()).statusCode(401);
        tokenPadrao();
        consultar(pix.id()).body("situacao", equalTo("EM_ANALISE"));
    }
}
