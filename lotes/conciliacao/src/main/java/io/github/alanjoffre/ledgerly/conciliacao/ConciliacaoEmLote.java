package io.github.alanjoffre.ledgerly.conciliacao;

import java.io.PrintStream;
import java.time.Instant;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * O programa de linha de comando: concilia uma base sintética, em sequência e em paralelo, e mede
 * o tempo de cada jeito. Ver {@code lotes/conciliacao/medir.sh}.
 *
 * <pre>
 * java --enable-preview -cp lotes/conciliacao/target/classes \
 *     io.github.alanjoffre.ledgerly.conciliacao.ConciliacaoEmLote [registros] [partes] [rodadas]
 * </pre>
 */
public final class ConciliacaoEmLote {

    /** As taxas de divergência da base sintética, por milhão de registros. */
    static final int FALTAS = 10;

    static final int SOBRAS = 5;
    static final int DIVERGENTES = 20;

    private ConciliacaoEmLote() {}

    public static void main(String[] args) throws Exception {
        long registros = args.length > 0 ? Long.parseLong(args[0]) : 10_000_000L;
        int partes = args.length > 1 ? Integer.parseInt(args[1]) : Runtime.getRuntime().availableProcessors();
        int rodadas = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        executar(registros, partes, rodadas, System.out);
    }

    /**
     * Mede as duas formas. Cada uma roda {@code rodadas} vezes e vale a mais rápida: as primeiras
     * pagam o aquecimento da JVM (o JIT compilando o código quente), que não é o que se quer medir.
     *
     * @return o resultado da conciliação em paralelo
     */
    static Resultado executar(long registros, int partes, int rodadas, PrintStream saida) throws Exception {
        Fonte[] lados = FonteSintetica.par(registros, FALTAS, SOBRAS, DIVERGENTES);
        var conciliador = new Conciliador(lados[0], lados[1]);
        String execucao = "conciliacao-" + Instant.now();

        saida.printf(Locale.ROOT, "Conciliação em lote: %,d registros por lado, %d faixas, %d núcleos, %d rodadas%n",
                registros, partes, Runtime.getRuntime().availableProcessors(), rodadas);

        Medida sequencia = medir(rodadas, () -> conciliador.conciliarEmSequencia(partes));
        Medida paralelo = medir(rodadas, () -> {
            try {
                return ScopedValue.where(Conciliador.EXECUCAO, execucao).call(() -> conciliador.conciliar(partes));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });

        saida.printf(Locale.ROOT, "Em sequência (1 thread):            %,6d ms  (%,.0f registros/s)%n",
                sequencia.milissegundos, sequencia.porSegundo(registros));
        saida.printf(Locale.ROOT, "Em paralelo (%3d virtual threads):  %,6d ms  (%,.0f registros/s)%n",
                partes, paralelo.milissegundos, paralelo.porSegundo(registros));
        saida.printf(Locale.ROOT, "Ganho: %.1fx%n", (double) sequencia.milissegundos / Math.max(1, paralelo.milissegundos));
        Resultado r = paralelo.resultado;
        saida.printf(Locale.ROOT, "Conferidos: %,d · conciliados: %,d · falta no razão: %,d · sobra no razão: %,d · divergentes: %,d%n",
                r.conferidos(), r.conciliados(), r.divergencias(Divergencia.Tipo.FALTA_NO_RAZAO),
                r.divergencias(Divergencia.Tipo.SOBRA_NO_RAZAO), r.divergencias(Divergencia.Tipo.DIVERGENTE));
        saida.printf("Os dois jeitos deram o mesmo resultado: %s%n",
                sequencia.resultado.conferidos() == r.conferidos()
                        && sequencia.resultado.divergencias().equals(r.divergencias()) ? "sim" : "NÃO");
        return r;
    }

    private record Medida(long milissegundos, Resultado resultado) {

        double porSegundo(long registros) {
            return registros * 1000.0 / Math.max(1, milissegundos);
        }
    }

    private static Medida medir(int rodadas, Supplier<Resultado> conciliacao) {
        long melhor = Long.MAX_VALUE;
        Resultado resultado = null;
        for (int i = 0; i < Math.max(1, rodadas); i++) {
            long inicio = System.nanoTime();
            resultado = conciliacao.get();
            melhor = Math.min(melhor, (System.nanoTime() - inicio) / 1_000_000);
        }
        return new Medida(melhor, resultado);
    }
}
