package io.github.alanjoffre.ledgerly.pix.integracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** O segundo clique: Redis na frente, o UNIQUE do banco atrás. */
@QuarkusTest
@WithTestResource(ServicosFalsos.class)
@DisplayName("Idempotência do Pix")
class IdempotenciaIT extends TesteDeIntegracao {

    @Test
    @DisplayName("o mesmo pedido de novo: 200 + Idempotent-Replayed, a mesma transferência, nada se move")
    void repetido() {
        Conta joao = novaConta("João", "100.00");
        Conta maria = novaConta("Maria", "0.00");
        String chave = chaveEmail(maria);
        String k = chave();

        String id = pix(k, joao.id(), chave, "10.00").statusCode(201).extract().path("id");
        pix(k, joao.id(), chave, "10.00")
                .statusCode(200)
                .header("Idempotent-Replayed", "true")
                .body("id", equalTo(id));

        assertThat(ServicosFalsos.RAZAO.saldo(joao.id())).isEqualByComparingTo("90.00");
    }

    @Test
    @DisplayName("a mesma chave num pedido diferente: 422 chave-idempotencia-reutilizada")
    void reutilizada() {
        Conta joao = novaConta("João", "100.00");
        Conta maria = novaConta("Maria", "0.00");
        String chave = chaveEmail(maria);
        String k = chave();
        pix(k, joao.id(), chave, "10.00").statusCode(201);
        pix(k, joao.id(), chave, "11.00")
                .statusCode(422)
                .body("type", equalTo("urn:ledgerly:erro:chave-idempotencia-reutilizada"));
    }

    @Test
    @DisplayName("pedido igual ainda em andamento (a trava do Redis está com outro): 409 + Retry-After")
    void emAndamento() {
        Conta joao = novaConta("João", "100.00");
        Conta maria = novaConta("Maria", "0.00");
        String chave = chaveEmail(maria);
        String k = chave();
        redis.value(String.class).psetex("pix:pedido:" + k, Duration.ofSeconds(30).toMillis(), "1");

        pix(k, joao.id(), chave, "10.00")
                .statusCode(409)
                .header("Retry-After", "1")
                .body("type", equalTo("urn:ledgerly:erro:pedido-em-andamento"));
        assertThat(ServicosFalsos.RAZAO.lancamentos()).isZero();
    }

    @Test
    @DisplayName("50 cliques ao mesmo tempo no mesmo Pix: 1 transferência, 1 débito")
    void cliquesSimultaneos() throws Exception {
        Conta joao = novaConta("João", "1000.00");
        Conta maria = novaConta("Maria", "0.00");
        String chave = chaveEmail(maria);
        String k = chave();
        int cliques = 50;
        var largada = new CountDownLatch(1);
        Map<Integer, AtomicInteger> porStatus = new ConcurrentHashMap<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Integer>> tarefas = IntStream.range(0, cliques).<Callable<Integer>>mapToObj(i -> () -> {
                largada.await();
                return pix(k, joao.id(), chave, "25.00").extract().statusCode();
            }).toList();
            List<Future<Integer>> resultados = tarefas.stream().map(executor::submit).toList();
            largada.countDown();
            for (Future<Integer> r : resultados) {
                porStatus.computeIfAbsent(r.get(), s -> new AtomicInteger()).incrementAndGet();
            }
        }

        assertThat(porStatus.keySet()).isSubsetOf(201, 200, 409);
        assertThat(porStatus.get(201).get()).isEqualTo(1);
        assertThat(contarTransferencias()).isEqualTo(1);
        assertThat(ServicosFalsos.RAZAO.saldo(joao.id())).isEqualByComparingTo("975.00");
        assertThat(ServicosFalsos.RAZAO.saldo(maria.id())).isEqualByComparingTo("25.00");
    }

    private long contarTransferencias() throws Exception {
        try (Connection c = banco.getConnection();
                var sql = c.createStatement();
                ResultSet rs = sql.executeQuery("SELECT count(*) FROM transferencia_pix")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
