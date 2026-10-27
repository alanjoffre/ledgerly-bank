package io.github.alanjoffre.ledgerly.pix.integracao;

import static io.restassured.RestAssured.given;

import io.github.alanjoffre.ledgerly.pix.dominio.CpfsDeTeste;
import io.quarkus.redis.datasource.RedisDataSource;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base dos testes de integração do Pix: Quarkus de verdade, com PostgreSQL, Kafka e Redis em
 * contêiner (Dev Services), e o razão e as contas falsos ({@link ServicosFalsos}).
 *
 * <p>Antes de cada teste: banco, Redis e serviços falsos zerados.
 */
abstract class TesteDeIntegracao {

    @Inject
    DataSource banco;

    @Inject
    RedisDataSource redis;

    @BeforeEach
    void zerar() throws SQLException {
        ServicosFalsos.RAZAO.zerar();
        ServicosFalsos.CONTAS.zerar();
        try (Connection c = banco.getConnection(); var sql = c.createStatement()) {
            sql.execute("TRUNCATE chave_pix, transferencia_pix, outbox");
        }
        redis.flushall();
    }

    /** Uma conta ativa, com titular, CPF gerado e saldo no razão falso. */
    protected record Conta(UUID id, String nome, String cpf) {}

    protected Conta novaConta(String nome, String saldo) {
        Conta conta = new Conta(UUID.randomUUID(), nome, CpfsDeTeste.gerar());
        ServicosFalsos.CONTAS.conta(conta.id(), nome, conta.cpf());
        ServicosFalsos.RAZAO.conta(conta.id(), saldo);
        return conta;
    }

    protected ValidatableResponse registrarChave(UUID contaId, String tipo, String valor) {
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("contaId", contaId.toString());
        corpo.put("tipo", tipo);
        if (valor != null) {
            corpo.put("valor", valor);
        }
        return given().contentType(ContentType.JSON).body(corpo).when().post("/v1/chaves").then();
    }

    /** Registra uma chave e-mail e devolve o valor dela. */
    protected String chaveEmail(Conta conta) {
        String nome = Normalizer.normalize(conta.nome(), Normalizer.Form.NFD).replaceAll("[^A-Za-z ]", "");
        String email = nome.toLowerCase().replace(' ', '.') + "." + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.com";
        registrarChave(conta.id(), "EMAIL", email).statusCode(201);
        return email;
    }

    protected ValidatableResponse pix(String chaveIdempotencia, UUID pagador, String chave, String valor) {
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("pagadorContaId", pagador.toString());
        corpo.put("chave", chave);
        corpo.put("valor", valor);
        corpo.put("descricao", "teste");
        return given().contentType(ContentType.JSON)
                .header("Idempotency-Key", chaveIdempotencia)
                .body(corpo)
                .when().post("/v1/transferencias")
                .then();
    }

    protected ValidatableResponse consultar(String id) {
        return given().when().get("/v1/transferencias/{id}", id).then();
    }

    protected static String chave() {
        return UUID.randomUUID().toString();
    }
}
