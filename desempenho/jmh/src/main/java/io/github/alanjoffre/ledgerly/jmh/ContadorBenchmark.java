package io.github.alanjoffre.ledgerly.jmh;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * A conta quente, em miniatura: um contador que 16 threads incrementam ao mesmo tempo.
 *
 * <p>É o mesmo problema do Caixa no razão (ADR-0019), só que na memória em vez de no banco. Quatro
 * jeitos de proteger o contador:
 *
 * <ul>
 *   <li>{@code synchronized} — a trava embutida do Java: uma thread por vez;</li>
 *   <li>{@link ReentrantLock} — a trava explícita: o mesmo, com mais controle;</li>
 *   <li>{@link AtomicLong} — sem trava: tenta gravar e, se outra gravou antes, tenta de novo
 *       (compare-and-swap, CAS). Com muita disputa, as tentativas se repetem;</li>
 *   <li>{@link LongAdder} — várias células, cada thread na sua, somadas na leitura. É a solução das
 *       sub-contas: dividir a linha disputada.</li>
 * </ul>
 *
 * <p>Medido em vazão: operações por microssegundo, somando as 16 threads.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(16)
public class ContadorBenchmark {

    private final Object trava = new Object();
    private long comSynchronized;

    private final ReentrantLock travaExplicita = new ReentrantLock();
    private long comReentrantLock;

    private final AtomicLong atomico = new AtomicLong();
    private final LongAdder somador = new LongAdder();

    @Benchmark
    public long synchronizedNoMesmoContador() {
        synchronized (trava) {
            return ++comSynchronized;
        }
    }

    @Benchmark
    public long reentrantLockNoMesmoContador() {
        travaExplicita.lock();
        try {
            return ++comReentrantLock;
        } finally {
            travaExplicita.unlock();
        }
    }

    @Benchmark
    public long atomicLongNoMesmoContador() {
        return atomico.incrementAndGet();
    }

    @Benchmark
    public void longAdderEmCelulas() {
        somador.increment();
    }
}
