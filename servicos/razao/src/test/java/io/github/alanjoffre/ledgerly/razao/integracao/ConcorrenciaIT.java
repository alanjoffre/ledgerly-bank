package io.github.alanjoffre.ledgerly.razao.integracao;

import static io.github.alanjoffre.ledgerly.nucleo.Dinheiro.reais;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.alanjoffre.ledgerly.razao.aplicacao.Comandos;
import io.github.alanjoffre.ledgerly.razao.dominio.ChaveIdempotencia;
import io.github.alanjoffre.ledgerly.razao.dominio.ChaveIdempotenciaReutilizadaException;
import io.github.alanjoffre.ledgerly.razao.dominio.SaldoInsuficienteException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A prova de que o razão aguenta pedidos simultâneos.
 *
 * <p>Como funciona: cada pedido roda numa <i>virtual thread</i> (Java 21+), e todas esperam numa
 * "largada" ({@link CountDownLatch}) para disparar juntas — o pior caso possível de disputa pela
 * mesma conta. Depois, o resultado é conferido contra o banco e contra a integridade do razão.
 */
@DisplayName("Concorrência: o razão sob pedidos simultâneos")
class ConcorrenciaIT extends TesteDeIntegracao {

    private static final Duration TEMPO_MAXIMO = Duration.ofMinutes(2);

    @Test
    @DisplayName("1.500 saques simultâneos de R$ 1,00 numa conta com R$ 1.000,00: "
            + "exatamente 1.000 passam, 500 são recusados, saldo final zero — sem gasto duplo")
    void semGastoDuplo() throws Exception {
        UUID conta = novaContaCom("1000.00");

        List<Class<?>> resultados = emParalelo(1_500, i -> () -> {
            lancamentos.sacar(new Comandos.Saque(chave(), conta, reais("1.00"), null));
            return null;
        });

        assertThat(contar(resultados))
                .containsEntry(Sucesso.class, 1_000L)
                .containsEntry(SaldoInsuficienteException.class, 500L)
                .hasSize(2);
        assertThat(saldo(conta).ehZero()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM partida WHERE conta_id = :c AND tipo = 'DEBITO'")
                        .param("c", conta).query(Long.class).single())
                .isEqualTo(1_000L);
        assertThat(contas.verificarIntegridade().integro()).isTrue();
    }

    @Test
    @DisplayName("400 transferências cruzadas A→B e B→A ao mesmo tempo: nenhum deadlock, "
            + "e o total das duas contas não muda")
    void semDeadlock() throws Exception {
        UUID a = novaContaCom("1000.00");
        UUID b = novaContaCom("1000.00");

        List<Class<?>> resultados = emParalelo(400, i -> () -> {
            boolean ida = i % 2 == 0;
            lancamentos.transferir(new Comandos.Transferencia(chave(), ida ? a : b, ida ? b : a, reais("1.00"), null));
            return null;
        });

        // Todas passam: nenhuma foi vítima de deadlock (o PostgreSQL abortaria uma delas).
        assertThat(contar(resultados)).containsOnlyKeys(Sucesso.class).containsEntry(Sucesso.class, 400L);
        assertThat(saldo(a)).isEqualTo(reais("1000.00"));
        assertThat(saldo(b)).isEqualTo(reais("1000.00"));
        assertThat(contas.verificarIntegridade().integro()).isTrue();
    }

    @Test
    @DisplayName("100 cliques simultâneos no mesmo depósito (mesma chave): 1 lançamento, 99 respostas repetidas")
    void idempotenciaSobDisputa() throws Exception {
        UUID conta = novaConta();
        ChaveIdempotencia chave = chave();

        List<Class<?>> resultados = emParalelo(100, i -> () -> {
            var r = lancamentos.depositar(new Comandos.Deposito(chave, conta, reais("10.00"), null));
            return r.repetido() ? Repetido.class : null;
        });

        assertThat(contar(resultados)).containsEntry(Sucesso.class, 1L).containsEntry(Repetido.class, 99L);
        assertThat(saldo(conta)).isEqualTo(reais("10.00"));
        assertThat(jdbc.sql("SELECT count(*) FROM lancamento").query(Long.class).single())
                .as("o depósito inicial não existe aqui; só o lançamento deste teste")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a mesma chave em transferências DIFERENTES, ao mesmo tempo: a restrição UNIQUE do banco "
            + "deixa passar uma só")
    void chaveDisputadaEntrePedidosDiferentes() throws Exception {
        ChaveIdempotencia chave = chave();
        int pares = 20;
        List<UUID[]> contasDosPares = new ArrayList<>();
        for (int i = 0; i < pares; i++) {
            contasDosPares.add(new UUID[] {novaContaCom("10.00"), novaConta()});
        }

        List<Class<?>> resultados = emParalelo(pares, i -> () -> {
            UUID[] par = contasDosPares.get(i);
            lancamentos.transferir(new Comandos.Transferencia(chave, par[0], par[1], reais("1.00"), null));
            return null;
        });

        assertThat(contar(resultados))
                .containsEntry(Sucesso.class, 1L)
                .containsEntry(ChaveIdempotenciaReutilizadaException.class, (long) pares - 1);
        assertThat(contas.verificarIntegridade().integro()).isTrue();
    }

    // ---- Infraestrutura do teste ----

    /** Marcadores de resultado. */
    private static final class Sucesso {}

    private static final class Repetido {}

    /**
     * Roda {@code quantidade} tarefas em virtual threads, todas liberadas ao mesmo tempo, e devolve,
     * para cada uma, {@link Sucesso} ou a classe da exceção que ela lançou.
     */
    private static List<Class<?>> emParalelo(int quantidade, Function<Integer, Callable<Class<?>>> tarefa)
            throws Exception {
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Class<?>>> futuros = new ArrayList<>(quantidade);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < quantidade; i++) {
                Callable<Class<?>> trabalho = tarefa.apply(i);
                futuros.add(executor.submit(() -> {
                    largada.await();
                    try {
                        Class<?> marcador = trabalho.call();
                        return marcador == null ? Sucesso.class : marcador;
                    } catch (RuntimeException e) {
                        return e.getClass();
                    }
                }));
            }
            largada.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(TEMPO_MAXIMO.toSeconds(), TimeUnit.SECONDS))
                    .as("as tarefas terminaram dentro de %s (sem travar)", TEMPO_MAXIMO)
                    .isTrue();
        }
        List<Class<?>> resultados = new ArrayList<>(quantidade);
        for (Future<Class<?>> futuro : futuros) {
            resultados.add(futuro.get());
        }
        return resultados;
    }

    private static java.util.Map<Class<?>, Long> contar(List<Class<?>> resultados) {
        return resultados.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }
}
