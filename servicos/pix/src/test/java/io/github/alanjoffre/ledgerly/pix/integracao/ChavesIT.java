package io.github.alanjoffre.ledgerly.pix.integracao;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;

import io.github.alanjoffre.ledgerly.pix.dominio.CpfsDeTeste;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@QuarkusTest
@WithTestResource(ServicosFalsos.class)
@DisplayName("Chaves Pix")
class ChavesIT extends TesteDeIntegracao {

    @Test
    @DisplayName("CPF do titular: 201; a consulta mostra o titular e a chave mascarada, sem o id da conta")
    void cpfDoTitular() {
        Conta maria = novaConta("Maria Souza", "0.00");
        registrarChave(maria.id(), "CPF", maria.cpf())
                .statusCode(201)
                .body("chave", equalTo(maria.cpf()))
                .body("titular", equalTo("Maria Souza"));

        given().get("/v1/chaves/{chave}", maria.cpf()).then()
                .statusCode(200)
                .body("titular", equalTo("Maria Souza"))
                .body("chave", equalTo("***.%s.%s-**".formatted(maria.cpf().substring(3, 6), maria.cpf().substring(6, 9))))
                .body("instituicao", equalTo("Ledgerly Bank"))
                .body("$", not(hasKey("contaId")));
    }

    @Test
    @DisplayName("CPF de outra pessoa: 422 titularidade-nao-confere")
    void cpfDeOutro() {
        Conta maria = novaConta("Maria", "0.00");
        registrarChave(maria.id(), "CPF", CpfsDeTeste.gerar())
                .statusCode(422)
                .body("type", equalTo("urn:ledgerly:erro:titularidade-nao-confere"));
    }

    @Test
    @DisplayName("aleatória: o banco gera; mandar o valor é erro")
    void aleatoria() {
        Conta maria = novaConta("Maria", "0.00");
        registrarChave(maria.id(), "ALEATORIA", null)
                .statusCode(201)
                .body("chave", matchesPattern("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
        registrarChave(maria.id(), "ALEATORIA", UUID.randomUUID().toString())
                .statusCode(400)
                .body("type", equalTo("urn:ledgerly:erro:chave-invalida"));
    }

    @Test
    @DisplayName("a mesma chave em duas contas: 409 chave-ja-cadastrada")
    void duplicada() {
        Conta maria = novaConta("Maria", "0.00");
        Conta joao = novaConta("João", "0.00");
        registrarChave(maria.id(), "TELEFONE", "+5511987654321").statusCode(201);
        registrarChave(joao.id(), "TELEFONE", "+55 (11) 98765-4321")
                .statusCode(409)
                .body("type", equalTo("urn:ledgerly:erro:chave-ja-cadastrada"));
    }

    @Test
    @DisplayName("limite de 5 chaves por conta: a sexta é 422 limite-de-chaves")
    void limite() {
        Conta maria = novaConta("Maria", "0.00");
        for (int i = 0; i < 5; i++) {
            registrarChave(maria.id(), "ALEATORIA", null).statusCode(201);
        }
        registrarChave(maria.id(), "ALEATORIA", null)
                .statusCode(422)
                .body("type", equalTo("urn:ledgerly:erro:limite-de-chaves"));
        given().get("/v1/contas/{id}/chaves", maria.id()).then().statusCode(200).body("$", hasSize(5));
    }

    @Test
    @DisplayName("conta inexistente: 404; bloqueada: 422; serviço de contas fora: 503")
    void conta() {
        registrarChave(UUID.randomUUID(), "ALEATORIA", null)
                .statusCode(404).body("type", equalTo("urn:ledgerly:erro:conta-nao-encontrada"));
        Conta maria = novaConta("Maria", "0.00");
        ServicosFalsos.CONTAS.bloquear(maria.id());
        registrarChave(maria.id(), "ALEATORIA", null)
                .statusCode(422).body("type", equalTo("urn:ledgerly:erro:conta-inativa"));
        ServicosFalsos.CONTAS.foraDoAr(true);
        registrarChave(maria.id(), "ALEATORIA", null)
                .statusCode(503).body("type", equalTo("urn:ledgerly:erro:servico-indisponivel"));
    }

    @Test
    @DisplayName("formato inválido: 400 com a mensagem; tipo inexistente: 400")
    void invalidas() {
        Conta maria = novaConta("Maria", "0.00");
        registrarChave(maria.id(), "EMAIL", "sem-arroba")
                .statusCode(400).body("type", equalTo("urn:ledgerly:erro:chave-invalida"));
        registrarChave(maria.id(), "FAX", "123").statusCode(400);
        given().contentType("application/json").body("{}").post("/v1/chaves").then()
                .statusCode(400).body("campos.contaId", not(equalTo(null)));
    }

    @Test
    @DisplayName("excluir: 204, e depois a chave não existe mais")
    void excluir() {
        Conta maria = novaConta("Maria", "0.00");
        String email = chaveEmail(maria);
        given().delete("/v1/chaves/{chave}", email).then().statusCode(204);
        given().get("/v1/chaves/{chave}", email).then().statusCode(404);
        given().delete("/v1/chaves/{chave}", email).then().statusCode(404);
    }
}
