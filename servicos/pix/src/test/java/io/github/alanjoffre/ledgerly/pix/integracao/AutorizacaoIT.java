package io.github.alanjoffre.ledgerly.pix.integracao;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Quem pode o quê no Pix (ADR-0025 e ADR-0026). O token passa pelo caminho da produção: descoberta
 * OIDC, chave pública (JWKS), emissor, validade, audiência e papéis.
 *
 * <p>BOLA (<i>Broken Object Level Authorization</i>, o 1º risco do OWASP API Top 10): o cliente
 * não pode usar a conta, a chave ou o Pix de outro só trocando o id na URL. A resposta para o que é
 * de outro é <b>igual</b> à de um id que não existe — nem o status conta que o recurso existe.
 */
@QuarkusTest
@WithTestResource(ServicosFalsos.class)
@DisplayName("Autorização no Pix")
class AutorizacaoIT extends TesteDeIntegracao {

    private static Map<String, Object> corpoPix(UUID pagador, String chave) {
        return Map.of("pagadorContaId", pagador.toString(), "chave", chave, "valor", "10.00", "descricao", "teste");
    }

    private static Response pixComo(String token, String idempotencia, UUID pagador, String chave) {
        return given().header("Authorization", token)
                .header("Idempotency-Key", idempotencia)
                .contentType(ContentType.JSON)
                .body(corpoPix(pagador, chave))
                .when().post("/v1/transferencias");
    }

    /** A resposta de erro sem o id (que aparece no texto): o resto tem de ser idêntico. */
    private static String semId(Response resposta, UUID id) {
        return resposta.statusCode() + " " + resposta.asString().replace(id.toString(), "<id>");
    }

    @Nested
    @DisplayName("transferências")
    class Transferencias {

        @Test
        @DisplayName("o cliente paga com a conta dele; com a de outro, 404 igual ao de uma conta que não existe")
        void pagaSoComAContaDele() {
            Conta joao = novaConta("João", "100.00");
            Conta maria = novaConta("Maria", "0.00");
            String chave = chaveEmail(maria);
            String tokenJoao = TokensPix.cliente(joao.clienteId());
            String tokenMaria = TokensPix.cliente(maria.clienteId());

            pixComo(tokenJoao, chave(), joao.id(), chave).then().statusCode(201);

            Response deOutro = pixComo(tokenMaria, chave(), joao.id(), chave);
            UUID inexistente = UUID.randomUUID();
            Response quenaoExiste = pixComo(tokenMaria, chave(), inexistente, chave);
            assertThat(deOutro.statusCode()).isEqualTo(404);
            assertThat(semId(deOutro, joao.id())).isEqualTo(semId(quenaoExiste, inexistente));
            assertThat(ServicosFalsos.RAZAO.saldo(joao.id())).isEqualByComparingTo("90.00");
        }

        @Test
        @DisplayName("a chave de idempotência de outro cliente não vira um jeito de ler o Pix dele")
        void repeticaoNaoVaza() {
            Conta joao = novaConta("João", "100.00");
            Conta maria = novaConta("Maria", "0.00");
            String chave = chaveEmail(maria);
            String k = chave();
            pixComo(TokensPix.cliente(joao.clienteId()), k, joao.id(), chave).then().statusCode(201);

            pixComo(TokensPix.cliente(maria.clienteId()), k, joao.id(), chave)
                    .then().statusCode(404).body("type", equalTo("urn:ledgerly:erro:conta-nao-encontrada"));
        }

        @Test
        @DisplayName("consulta: o dono, o analista e um serviço veem; outro cliente recebe 404 igual ao de um Pix que não existe")
        void consulta() {
            Conta joao = novaConta("João", "100.00");
            Conta maria = novaConta("Maria", "0.00");
            String id = pix(chave(), joao.id(), chaveEmail(maria), "10.00").statusCode(201).extract().path("id");

            given().header("Authorization", TokensPix.cliente(joao.clienteId()))
                    .get("/v1/transferencias/{id}", id).then().statusCode(200);
            given().header("Authorization", TokensPix.analista())
                    .get("/v1/transferencias/{id}", id).then().statusCode(200);
            given().header("Authorization", TokensPix.servico())
                    .get("/v1/transferencias/{id}", id).then().statusCode(200);

            // A Maria recebeu o Pix, mas quem o fez foi o João: a consulta da transferência é de quem pagou.
            Response deOutro = given().header("Authorization", TokensPix.cliente(maria.clienteId()))
                    .get("/v1/transferencias/{id}", id);
            UUID inexistente = UUID.randomUUID();
            Response quenaoExiste = given().header("Authorization", TokensPix.cliente(maria.clienteId()))
                    .get("/v1/transferencias/{id}", inexistente);
            assertThat(deOutro.statusCode()).isEqualTo(404);
            assertThat(semId(deOutro, UUID.fromString(id))).isEqualTo(semId(quenaoExiste, inexistente));
        }

        @Test
        @DisplayName("o analista não faz Pix (403); a fila de análise é só dele (o cliente recebe 403)")
        void papeis() {
            Conta joao = novaConta("João", "100.00");
            Conta maria = novaConta("Maria", "0.00");
            String chave = chaveEmail(maria);

            pixComo(TokensPix.analista(), chave(), joao.id(), chave)
                    .then().statusCode(403).body("type", equalTo("urn:ledgerly:erro:acesso-negado"));
            given().header("Authorization", TokensPix.cliente(joao.clienteId()))
                    .get("/v1/transferencias?situacao=EM_ANALISE").then().statusCode(403);
            given().header("Authorization", TokensPix.analista())
                    .get("/v1/transferencias?situacao=EM_ANALISE").then().statusCode(200);
            given().header("Authorization", TokensPix.analista())
                    .get("/v1/transferencias?situacao=CONCLUIDA").then().statusCode(400);
        }

        @Test
        @DisplayName("token sem papel ou com cliente_id que não é UUID: nunca é dono")
        void tokensEstranhos() {
            Conta joao = novaConta("João", "100.00");
            Conta maria = novaConta("Maria", "0.00");
            String chave = chaveEmail(maria);

            pixComo(TokensPix.token().clienteId(joao.clienteId()).bearer(), chave(), joao.id(), chave)
                    .then().statusCode(403);
            pixComo(TokensPix.token().papeis("cliente").clienteIdTexto("nao-e-uuid").bearer(), chave(), joao.id(), chave)
                    .then().statusCode(404);
            pixComo(TokensPix.token().papeis("cliente").bearer(), chave(), joao.id(), chave)
                    .then().statusCode(404);
        }
    }

    @Nested
    @DisplayName("chaves e QR Code")
    class Chaves {

        @Test
        @DisplayName("cadastrar, listar, remover e gerar QR Code: só o dono; a consulta da chave é de todos")
        void soODono() {
            Conta joao = novaConta("João", "0.00");
            Conta maria = novaConta("Maria", "0.00");
            String tokenJoao = TokensPix.cliente(joao.clienteId());
            String tokenMaria = TokensPix.cliente(maria.clienteId());
            String email = "joao." + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.com";

            given().header("Authorization", tokenJoao).contentType(ContentType.JSON)
                    .body(Map.of("contaId", joao.id().toString(), "tipo", "EMAIL", "valor", email))
                    .post("/v1/chaves").then().statusCode(201);
            given().header("Authorization", tokenMaria).contentType(ContentType.JSON)
                    .body(Map.of("contaId", joao.id().toString(), "tipo", "ALEATORIA"))
                    .post("/v1/chaves").then().statusCode(404);

            given().header("Authorization", tokenJoao).get("/v1/contas/{id}/chaves", joao.id())
                    .then().statusCode(200).body("size()", equalTo(1));
            given().header("Authorization", tokenMaria).get("/v1/contas/{id}/chaves", joao.id())
                    .then().statusCode(404);

            // "Confira o destinatário": qualquer cliente consulta, antes de pagar.
            given().header("Authorization", tokenMaria).get("/v1/chaves/{chave}", email)
                    .then().statusCode(200).body("titular", equalTo("João"));

            given().header("Authorization", tokenMaria).get("/v1/chaves/{chave}/qrcode", email).then().statusCode(404);
            given().header("Authorization", tokenJoao).get("/v1/chaves/{chave}/qrcode", email).then().statusCode(200);

            given().header("Authorization", tokenMaria).delete("/v1/chaves/{chave}", email).then().statusCode(404);
            given().header("Authorization", tokenJoao).get("/v1/chaves/{chave}", email).then().statusCode(200);
            given().header("Authorization", tokenJoao).delete("/v1/chaves/{chave}", email).then().statusCode(204);
        }
    }

    @Nested
    @DisplayName("token")
    class Token {

        @Test
        @DisplayName("sem token, vencido, de outro emissor, de outra audiência ou forjado: 401 com WWW-Authenticate")
        void recusados() {
            Conta joao = novaConta("João", "0.00");
            for (String token : new String[] {
                TokensPix.token().papeis("analista").vencido().bearer(),
                TokensPix.token().papeis("analista").emissor("http://outro/realms/ledgerly").bearer(),
                TokensPix.token().papeis("analista").audiencia("outra-api").bearer(),
                TokensPix.token().papeis("analista").assinadoPorOutraChave().bearer(),
                "Bearer nao-e-um-jwt"
            }) {
                given().header("Authorization", token)
                        .get("/v1/contas/{id}/chaves", joao.id())
                        .then().statusCode(401)
                        .header("WWW-Authenticate", startsWith("Bearer"))
                        .body("type", equalTo("urn:ledgerly:erro:nao-autenticado"));
            }
            RestAssured.requestSpecification = null;
            given().get("/v1/contas/{id}/chaves", joao.id()).then().statusCode(401);
        }

        @Test
        @DisplayName("a saúde (/q/health) responde sem token — o Kubernetes pergunta sem login")
        void saudeAberta() {
            RestAssured.requestSpecification = null;
            given().get("/q/health/ready").then().statusCode(200);
        }

        @Test
        @DisplayName("o Pix chama o razão e as contas com o token dele (que os falsos exigem), pedido uma vez e reaproveitado")
        void tokenDeServico() {
            Conta joao = novaConta("João", "100.00");
            Conta maria = novaConta("Maria", "0.00");
            String chave = chaveEmail(maria);
            pix(chave(), joao.id(), chave, "1.00").statusCode(201);
            int antes = ServicosFalsos.TOKENS_EMITIDOS.get();
            for (int i = 0; i < 5; i++) {
                pix(chave(), joao.id(), chave, "1.00").statusCode(201).body("situacao", equalTo("CONCLUIDA"));
            }
            assertThat(antes).isPositive();
            assertThat(ServicosFalsos.TOKENS_EMITIDOS.get()).isEqualTo(antes);
        }
    }
}
