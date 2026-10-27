package io.github.alanjoffre.ledgerly.pix.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Redis fora do ar (apontado para uma porta onde não há nada): a trava rápida deixa passar
 * (falha aberta), e o {@code UNIQUE} do PostgreSQL segura sozinho os cliques simultâneos.
 */
@QuarkusTest
@TestProfile(RedisForaDoArIT.SemRedis.class)
@WithTestResource(ServicosFalsos.class)
@DisplayName("Pix com o Redis fora do ar")
class RedisForaDoArIT extends TesteDeIntegracao {

    public static class SemRedis implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.redis.hosts", "redis://127.0.0.1:1", "quarkus.redis.devservices.enabled", "false");
        }
    }

    @Override
    @BeforeEach
    void zerar() throws java.sql.SQLException {
        ServicosFalsos.RAZAO.zerar();
        ServicosFalsos.CONTAS.zerar();
        try (Connection c = banco.getConnection(); var sql = c.createStatement()) {
            sql.execute("TRUNCATE chave_pix, transferencia_pix, outbox");
        }
    }

    @Test
    @DisplayName("20 cliques simultâneos sem Redis: o banco garante 1 transferência e 1 débito")
    void bancoSeguraSozinho() throws Exception {
        Conta joao = novaConta("João", "100.00");
        Conta maria = novaConta("Maria", "0.00");
        String email = chaveEmail(maria);
        String k = chave();
        var largada = new CountDownLatch(1);
        List<Future<Integer>> respostas = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 20; i++) {
                respostas.add(executor.submit(() -> {
                    largada.await();
                    return pix(k, joao.id(), email, "10.00").extract().statusCode();
                }));
            }
            largada.countDown();
            for (Future<Integer> r : respostas) {
                assertThat(r.get()).isIn(200, 201);
            }
        }
        try (Connection c = banco.getConnection();
                var sql = c.createStatement();
                ResultSet rs = sql.executeQuery("SELECT count(*) FROM transferencia_pix")) {
            rs.next();
            assertThat(rs.getLong(1)).isEqualTo(1);
        }
        assertThat(ServicosFalsos.RAZAO.saldo(joao.id())).isEqualByComparingTo("90.00");
    }
}
