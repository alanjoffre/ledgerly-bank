package io.github.alanjoffre.ledgerly.jmh;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Virtual threads × threads de plataforma, com 10 mil tarefas.
 *
 * <p>Uma thread de plataforma é uma thread do sistema operacional: cara (cerca de 1 MB de pilha),
 * então se usa um grupo fixo delas (um pool), como o Tomcat faz com 200. Uma virtual thread é do
 * Java: barata, uma por tarefa. Quando ela espera (banco, rede), sai da thread do sistema e deixa
 * outra rodar.
 *
 * <p>Três executores, todos criados uma vez só, na preparação (como num servidor):
 *
 * <ul>
 *   <li>uma virtual thread por tarefa;</li>
 *   <li>um pool de 200 threads de plataforma, o tamanho padrão do Tomcat;</li>
 *   <li>um pool de threads de plataforma do tamanho da máquina (uma por thread de hardware).</li>
 * </ul>
 *
 * <p>Dois tipos de tarefa:
 *
 * <ul>
 *   <li>{@code espera} — 10 ms parada, como quem espera o banco responder. Aqui a virtual thread
 *       deve ganhar: 10 mil esperas ao mesmo tempo, contra 200 de cada vez no pool;</li>
 *   <li>{@code calculo} — só CPU. Aqui ela NÃO deve ganhar do pool do tamanho da máquina: a CPU é
 *       a mesma, e não há espera para aproveitar.</li>
 * </ul>
 *
 * <p>Medido em tempo por rodada (as 10 mil tarefas), em milissegundos.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class ThreadsBenchmark {

    private static final int TAREFAS = 10_000;

    /** O tamanho do pool de threads de plataforma: o padrão do Tomcat. */
    private static final int POOL = 200;

    @Param({"espera", "calculo"})
    public String tarefa;

    private ExecutorService virtuais;
    private ExecutorService poolDoTomcat;
    private ExecutorService poolDaMaquina;

    @Setup
    public void preparar() {
        virtuais = Executors.newVirtualThreadPerTaskExecutor();
        poolDoTomcat = Executors.newFixedThreadPool(POOL);
        poolDaMaquina = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    }

    @TearDown
    public void encerrar() {
        virtuais.close();
        poolDoTomcat.close();
        poolDaMaquina.close();
    }

    @Benchmark
    public long virtualThreads() throws Exception {
        return rodar(virtuais);
    }

    @Benchmark
    public long threadsDePlataformaEmPool() throws Exception {
        return rodar(poolDoTomcat);
    }

    @Benchmark
    public long threadsDePlataformaDoTamanhoDaMaquina() throws Exception {
        return rodar(poolDaMaquina);
    }

    private long rodar(ExecutorService executor) throws Exception {
        boolean espera = tarefa.equals("espera");
        List<Future<Long>> futuros = new ArrayList<>(TAREFAS);
        for (int i = 0; i < TAREFAS; i++) {
            long semente = i;
            futuros.add(executor.submit(() -> espera ? esperar() : calcular(semente)));
        }
        long soma = 0;
        for (Future<Long> futuro : futuros) {
            soma += futuro.get();
        }
        return soma;
    }

    private static long esperar() throws InterruptedException {
        Thread.sleep(10);
        return 1;
    }

    /**
     * Uns 50 microssegundos de conta, sem esperar nada (medido: cerca de 1,1 ns por volta).
     *
     * <p>Cada volta depende da anterior, e o deslocamento com ou-exclusivo impede o JIT de encurtar
     * o laço. A primeira versão usava só {@code z * a + c}: o JIT condensou as 20 mil voltas, cada
     * tarefa custava 0,75 µs em vez de 50, e o benchmark media a criação das tarefas, e não a conta
     * (ADR-0023).
     */
    private static long calcular(long z) {
        for (int i = 0; i < 45_000; i++) {
            z = (z ^ (z >>> 31)) * 0xBF58476D1CE4E5B9L;
        }
        return z;
    }
}
