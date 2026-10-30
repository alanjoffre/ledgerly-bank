package io.github.alanjoffre.ledgerly.pix.integracao;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.alanjoffre.ledgerly.pix.integracao.ServicosFalsos.AntifraudeFalso;
import io.github.alanjoffre.ledgerly.pix.integracao.ServicosFalsos.RazaoFalso;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O antifraude na Saga do Pix (ADR-0032 e ADR-0033): todo Pix é avaliado antes de o pagador ser
 * debitado. Aprovado, segue; segurado, fica RETIDO até uma analista liberar ou recusar; recusado,
 * termina sem dinheiro movido. Sem resposta do antifraude, vale a falha segura: até R$ 500,00
 * segue, acima disso fica retido.
 */
@QuarkusTest
@WithTestResource(ServicosFalsos.class)
@DisplayName("Antifraude na Saga do Pix")
class AntifraudeIT extends TesteDeIntegracao {

    private static final RazaoFalso RAZAO = ServicosFalsos.RAZAO;
    private static final AntifraudeFalso ANTIFRAUDE = ServicosFalsos.ANTIFRAUDE;
    private static final String JUSTIFICATIVA = "Cliente confirmou o Pix por telefone, na gravação 4412.";

    private record Pix(Conta pagador, Conta recebedor, String chave) {}

    private Pix contas() {
        Conta joao = novaConta("João", "10000.00");
        Conta maria = novaConta("Maria", "0.00");
        return new Pix(joao, maria, chaveEmail(maria));
    }

    private static ValidatableResponse decidir(String id, String rota, String justificativa) {
        return given().header("Authorization", TokensPix.token().nome("carla").sujeito("id-da-carla").papeis("analista").bearer())
                .contentType(ContentType.JSON)
                .body(Map.of("justificativa", justificativa))
                .when().post("/v1/transferencias/{id}/" + rota, id)
                .then();
    }

    private String retido(Pix pix, String valor) {
        ANTIFRAUDE.decidir("SEGURAR", 770, "valor de R$ 4.800,00", "primeiro Pix do pagador para esta conta");
        return pix(chave(), pix.pagador().id(), pix.chave(), valor)
                .statusCode(202)
                .body("situacao", equalTo("RETIDA"))
                .extract().path("id");
    }

    @Test
    @DisplayName("aprovado: o Pix segue até CONCLUIDA, com a nota de risco na resposta")
    void aprovado() {
        Pix pix = contas();
        ANTIFRAUDE.decidir("APROVAR", 37);

        pix(chave(), pix.pagador().id(), pix.chave(), "120.00")
                .statusCode(201)
                .body("situacao", equalTo("CONCLUIDA"))
                .body("notaDeRisco", equalTo(37));
        assertThat(RAZAO.saldo(pix.recebedor().id())).isEqualByComparingTo("120.00");
    }

    @Test
    @DisplayName("o pedido leva o que o antifraude precisa: contas, valor, instante e a abertura da conta recebedora")
    void pedidoDeAvaliacao() {
        Pix pix = contas();
        Instant abertura = Instant.parse("2026-10-26T09:15:00Z");
        ServicosFalsos.CONTAS.abertaEm(pix.recebedor().id(), abertura);

        String id = pix(chave(), pix.pagador().id(), pix.chave(), "1200.00").statusCode(201).extract().path("id");
        String criadaEm = consultar(id).extract().path("criadaEm");

        assertThat(ANTIFRAUDE.pedidos()).hasSize(1);
        JsonNode pedido = ANTIFRAUDE.pedidos().getFirst();
        assertThat(pedido.path("transferenciaId").asText()).isEqualTo(id);
        assertThat(pedido.path("pagadorContaId").asText()).isEqualTo(pix.pagador().id().toString());
        assertThat(pedido.path("recebedorContaId").asText()).isEqualTo(pix.recebedor().id().toString());
        assertThat(pedido.path("valor").asText()).isEqualTo("1200.00");
        assertThat(Instant.parse(pedido.path("instante").asText())).isEqualTo(Instant.parse(criadaEm));
        assertThat(Instant.parse(pedido.path("contaRecebedoraAbertaEm").asText())).isEqualTo(abertura);
    }

    @Test
    @DisplayName("segurado: 202 RETIDA, nenhum centavo sai, e o Pix entra na fila da analista")
    void segurado() {
        Pix pix = contas();
        String id = retido(pix, "4800.00");

        consultar(id).statusCode(200)
                .body("situacao", equalTo("RETIDA"))
                .body("notaDeRisco", equalTo(770))
                .body("motivo", equalTo("Retido pelo antifraude (nota 770): valor de R$ 4.800,00; primeiro Pix do pagador para esta conta"))
                .body("lancamentos.envio", nullValue());
        assertThat(RAZAO.saldo(pix.pagador().id())).isEqualByComparingTo("10000.00");
        assertThat(RAZAO.lancamentos()).isZero();

        given().header("Authorization", TokensPix.analista())
                .when().get("/v1/transferencias?situacao=RETIDA")
                .then().statusCode(200).body("id", hasItem(id));
    }

    @Test
    @DisplayName("recusado: REJEITADA na hora, sem débito")
    void recusado() {
        Pix pix = contas();
        ANTIFRAUDE.decidir("RECUSAR", 990, "8 Pix do pagador na última hora");

        pix(chave(), pix.pagador().id(), pix.chave(), "9000.00")
                .statusCode(201)
                .body("situacao", equalTo("REJEITADA"))
                .body("notaDeRisco", equalTo(990))
                .body("motivo", equalTo("Recusado pelo antifraude (nota 990): 8 Pix do pagador na última hora"));
        assertThat(RAZAO.lancamentos()).isZero();
    }

    @Test
    @DisplayName("antifraude fora do ar: até R$ 500,00 o Pix segue; acima, fica retido (falha segura)")
    void foraDoAr() {
        Pix pix = contas();
        ANTIFRAUDE.foraDoAr(true);

        pix(chave(), pix.pagador().id(), pix.chave(), "500.00")
                .statusCode(201)
                .body("situacao", equalTo("CONCLUIDA"))
                .body("notaDeRisco", nullValue());
        pix(chave(), pix.pagador().id(), pix.chave(), "500.01")
                .statusCode(202)
                .body("situacao", equalTo("RETIDA"))
                .body("notaDeRisco", nullValue())
                .body("motivo", startsWith("Retido sem avaliação: o valor passa de R$"));
        assertThat(RAZAO.saldo(pix.recebedor().id())).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("antifraude lento (mais que o limite de 800 ms): o Pix não espera, vale a falha segura")
    void lento() {
        Pix pix = contas();
        ANTIFRAUDE.demorar(1500);

        long inicio = System.nanoTime();
        pix(chave(), pix.pagador().id(), pix.chave(), "2000.00")
                .statusCode(202)
                .body("situacao", equalTo("RETIDA"));
        assertThat((System.nanoTime() - inicio) / 1_000_000).isLessThan(1500);
    }

    @Test
    @DisplayName("liberar: o Pix sai da fila e vai até CONCLUIDA; a decisão fica na trilha de auditoria")
    void liberar() throws Exception {
        Pix pix = contas();
        String id = retido(pix, "4800.00");

        decidir(id, "liberacao", JUSTIFICATIVA)
                .statusCode(200)
                .body("situacao", equalTo("CONCLUIDA"))
                .body("lancamentos.envio", notNullValue());
        assertThat(RAZAO.saldo(pix.recebedor().id())).isEqualByComparingTo("4800.00");
        assertThat(decisao(id)).isEqualTo("LIBERAR|id-da-carla|carla|null|" + JUSTIFICATIVA);

        given().header("Authorization", TokensPix.analista())
                .when().get("/v1/transferencias?situacao=RETIDA")
                .then().statusCode(200).body("id", not(hasItem(id)));
    }

    @Test
    @DisplayName("recusar: REJEITADA, sem débito, e o motivo diz que foi na análise")
    void recusar() throws Exception {
        Pix pix = contas();
        String id = retido(pix, "4800.00");

        decidir(id, "recusa", "Cliente não reconhece o Pix: golpe do falso parente.")
                .statusCode(200)
                .body("situacao", equalTo("REJEITADA"))
                .body("motivo", startsWith("Recusado na análise. Retido pelo antifraude (nota 770)"));
        assertThat(RAZAO.lancamentos()).isZero();
        assertThat(decisao(id)).startsWith("RECUSAR|id-da-carla|carla|null|");
    }

    @Test
    @DisplayName("a segunda decisão: 409, e o dinheiro não sai duas vezes")
    void decisaoRepetida() {
        Pix pix = contas();
        String id = retido(pix, "4800.00");
        decidir(id, "liberacao", JUSTIFICATIVA).statusCode(200);

        decidir(id, "liberacao", JUSTIFICATIVA)
                .statusCode(409)
                .body("type", equalTo("urn:ledgerly:erro:transferencia-fora-de-analise"));
        decidir(id, "recusa", JUSTIFICATIVA).statusCode(409);
        assertThat(RAZAO.saldo(pix.recebedor().id())).isEqualByComparingTo("4800.00");
    }

    @Test
    @DisplayName("liberar um Pix que não está retido: 409")
    void naoRetido() {
        Pix pix = contas();
        String id = pix(chave(), pix.pagador().id(), pix.chave(), "10.00").statusCode(201).extract().path("id");
        decidir(id, "liberacao", JUSTIFICATIVA).statusCode(409);
    }

    @Test
    @DisplayName("justificativa curta: 400")
    void justificativaCurta() {
        Pix pix = contas();
        String id = retido(pix, "4800.00");
        decidir(id, "recusa", "golpe").statusCode(400);
        consultar(id).body("situacao", equalTo("RETIDA"));
    }

    @Test
    @DisplayName("o cliente não libera o próprio Pix retido: 403")
    void clienteNaoLibera() {
        Pix pix = contas();
        String id = retido(pix, "4800.00");
        given().header("Authorization", TokensPix.cliente(pix.pagador().clienteId()))
                .contentType(ContentType.JSON)
                .body(Map.of("justificativa", JUSTIFICATIVA))
                .when().post("/v1/transferencias/{id}/liberacao", id)
                .then().statusCode(403);
        given().header("Authorization", TokensPix.cliente(pix.pagador().clienteId()))
                .when().get("/v1/transferencias?situacao=RETIDA")
                .then().statusCode(403);
        consultar(id).body("situacao", equalTo("RETIDA"));
    }

    @Test
    @DisplayName("o cliente não vê a nota nem os motivos do antifraude, no envio e na consulta; a analista vê")
    void clienteNaoVeAAnaliseDeRisco() {
        Pix pix = contas();
        String cliente = TokensPix.cliente(pix.pagador().clienteId());
        ANTIFRAUDE.decidir("SEGURAR", 770, "valor de R$ 4.800,00", "primeiro Pix do pagador para esta conta");

        String id = given().header("Authorization", cliente).contentType(ContentType.JSON)
                .header("Idempotency-Key", chave())
                .body(Map.of("pagadorContaId", pix.pagador().id().toString(), "chave", pix.chave(), "valor", "4800.00"))
                .when().post("/v1/transferencias")
                .then().statusCode(202)
                .body("situacao", equalTo("RETIDA"))
                .body("notaDeRisco", nullValue())
                .body("motivo", equalTo("Pix em análise de segurança antes de sair da sua conta. Nenhum dinheiro saiu."))
                .extract().path("id");
        given().header("Authorization", cliente).when().get("/v1/transferencias/{id}", id)
                .then().statusCode(200)
                .body("notaDeRisco", nullValue())
                .body("motivo", not(containsString("nota")));
        given().header("Authorization", TokensPix.analista()).when().get("/v1/transferencias/{id}", id)
                .then().statusCode(200)
                .body("notaDeRisco", equalTo(770))
                .body("motivo", startsWith("Retido pelo antifraude (nota 770)"));

        decidir(id, "recusa", "Cliente não reconhece o Pix: golpe do falso parente.").statusCode(200);
        given().header("Authorization", cliente).when().get("/v1/transferencias/{id}", id)
                .then().statusCode(200)
                .body("situacao", equalTo("REJEITADA"))
                .body("notaDeRisco", nullValue())
                .body("motivo", equalTo("Pix não autorizado pela análise de segurança. Nenhum dinheiro saiu da sua conta."));
    }

    @Test
    @DisplayName("Pix recusado por falta de saldo: o cliente lê o motivo de sempre, só sem a nota")
    void clienteVeMotivoQueNaoEDeRisco() {
        Conta pobre = novaConta("Pedro", "10.00");
        Conta maria = novaConta("Maria", "0.00");
        ANTIFRAUDE.decidir("APROVAR", 37);

        given().header("Authorization", TokensPix.cliente(pobre.clienteId())).contentType(ContentType.JSON)
                .header("Idempotency-Key", chave())
                .body(Map.of("pagadorContaId", pobre.id().toString(), "chave", chaveEmail(maria), "valor", "50.00"))
                .when().post("/v1/transferencias")
                .then().statusCode(201)
                .body("situacao", equalTo("REJEITADA"))
                .body("notaDeRisco", nullValue())
                .body("motivo", not(containsString("análise de segurança")));
    }

    /** A decisão gravada na auditoria, em "decisao|analista_id|analista_nome|conta_destino_id|justificativa". */
    private String decisao(String id) throws Exception {
        try (Connection c = banco.getConnection();
                PreparedStatement sql = c.prepareStatement(
                        "SELECT decisao, analista_id, analista_nome, conta_destino_id, justificativa FROM decisao_de_analise WHERE transferencia_id = ?")) {
            sql.setObject(1, UUID.fromString(id));
            try (ResultSet rs = sql.executeQuery()) {
                assertThat(rs.next()).isTrue();
                String linha = String.join("|", rs.getString(1), rs.getString(2), rs.getString(3),
                        String.valueOf(rs.getObject(4)), rs.getString(5));
                assertThat(rs.next()).isFalse();
                return linha;
            }
        }
    }
}
