package io.github.alanjoffre.ledgerly.jmh;

import java.math.BigDecimal;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Quanto custa o dinheiro em {@link BigDecimal} (a escolha dos serviços, ADR-0007) comparado com
 * centavos num {@code long} (a escolha da conciliação em lote, ADR-0022)?
 *
 * <p>Somar e comparar mil valores. O {@code BigDecimal} é um objeto: cada soma cria um novo, que o
 * coletor de lixo depois recolhe. O {@code long} é um número na CPU. A pergunta não é "qual é mais
 * rápido" (o {@code long}, claro), é "quanto": se a diferença some perto do custo de um acesso ao
 * banco, o {@code BigDecimal} fica onde está.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class DinheiroBenchmark {

    private static final int VALORES = 1000;

    private BigDecimal[] decimais;
    private BigDecimal[] outrosDecimais;
    private long[] centavos;
    private long[] outrosCentavos;

    @Setup
    public void preparar() {
        var sorteio = new SplittableRandom(42);
        decimais = new BigDecimal[VALORES];
        outrosDecimais = new BigDecimal[VALORES];
        centavos = new long[VALORES];
        outrosCentavos = new long[VALORES];
        for (int i = 0; i < VALORES; i++) {
            long c = 1 + sorteio.nextLong(500_000);
            centavos[i] = c;
            outrosCentavos[i] = c;
            decimais[i] = BigDecimal.valueOf(c, 2);
            outrosDecimais[i] = BigDecimal.valueOf(c, 2);
        }
    }

    @Benchmark
    public BigDecimal somarBigDecimal() {
        BigDecimal soma = BigDecimal.ZERO;
        for (BigDecimal v : decimais) {
            soma = soma.add(v);
        }
        return soma; // devolver o resultado impede o JIT de apagar a conta
    }

    @Benchmark
    public long somarLong() {
        long soma = 0;
        for (long v : centavos) {
            soma = Math.addExact(soma, v); // como o Dinheiro: estouro vira erro, não valor errado
        }
        return soma;
    }

    @Benchmark
    public int compararBigDecimal() {
        int iguais = 0;
        for (int i = 0; i < VALORES; i++) {
            if (decimais[i].compareTo(outrosDecimais[i]) == 0) {
                iguais++;
            }
        }
        return iguais;
    }

    @Benchmark
    public int compararLong() {
        int iguais = 0;
        for (int i = 0; i < VALORES; i++) {
            if (centavos[i] == outrosCentavos[i]) {
                iguais++;
            }
        }
        return iguais;
    }
}
