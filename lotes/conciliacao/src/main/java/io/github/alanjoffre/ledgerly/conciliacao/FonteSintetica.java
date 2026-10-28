package io.github.alanjoffre.ledgerly.conciliacao;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Lançamentos de Pix inventados, aos milhões, para medir a conciliação sem precisar de um banco
 * cheio. Dados sintéticos: nenhum dado de pessoa, e o mesmo resultado em toda execução.
 *
 * <p><b>Como os ids saem em ordem sem ordenar nada:</b> o registro {@code i} tem os 64 bits altos
 * do id iguais a {@code i × passo}, com o passo = 2^64 / registros. Então os ids já nascem
 * crescentes e espalhados pelo espaço inteiro, e uma faixa sabe direto por qual {@code i} começar.
 * Memória constante: nada é guardado, cada registro é calculado quando é lido.
 *
 * <p><b>As divergências</b> só existem do lado do razão, em posições sorteadas de forma fixa (a
 * partir de {@code i}): uma parte por milhão de cada tipo. O lado do Pix é a verdade.
 */
public final class FonteSintetica implements Fonte {

    public enum Lado {
        PIX,
        RAZAO
    }

    private static final int MILHAO = 1_000_000;

    private final long registros;
    private final Lado lado;
    private final int faltasPorMilhao;
    private final int sobrasPorMilhao;
    private final int divergentesPorMilhao;
    private final long passo;

    /**
     * @param registros            quantos lançamentos o Pix tem
     * @param lado                 qual dos lados esta fonte representa
     * @param faltasPorMilhao      no razão: lançamentos do Pix que não estão lá
     * @param sobrasPorMilhao      no razão: lançamentos a mais, que o Pix não conhece
     * @param divergentesPorMilhao no razão: lançamentos com um centavo a mais
     */
    public FonteSintetica(
            long registros, Lado lado, int faltasPorMilhao, int sobrasPorMilhao, int divergentesPorMilhao) {
        if (registros < 1 || registros > (1L << 40)) {
            throw new IllegalArgumentException("Número de registros fora do intervalo: " + registros);
        }
        if (faltasPorMilhao < 0 || sobrasPorMilhao < 0 || divergentesPorMilhao < 0
                || (long) faltasPorMilhao + divergentesPorMilhao > MILHAO || sobrasPorMilhao > MILHAO) {
            throw new IllegalArgumentException("Taxas de divergência inválidas");
        }
        this.registros = registros;
        this.lado = lado;
        this.faltasPorMilhao = faltasPorMilhao;
        this.sobrasPorMilhao = sobrasPorMilhao;
        this.divergentesPorMilhao = divergentesPorMilhao;
        // Sempre >= 2^24: o id da sobra (alto + 1) cabe antes do próximo registro.
        this.passo = Long.divideUnsigned(-1L, registros);
    }

    /** Os dois lados de uma mesma base: o Pix e o razão com as divergências. */
    public static Fonte[] par(long registros, int faltasPorMilhao, int sobrasPorMilhao, int divergentesPorMilhao) {
        return new Fonte[] {
            new FonteSintetica(registros, Lado.PIX, 0, 0, 0),
            new FonteSintetica(registros, Lado.RAZAO, faltasPorMilhao, sobrasPorMilhao, divergentesPorMilhao)
        };
    }

    @Override
    public Stream<Registro> ler(Faixa faixa) {
        long primeiro = Math.max(0, Long.divideUnsigned(faixa.inicio().getMostSignificantBits(), passo) - 1);
        var registrosDaFaixa = new Iterator<Registro>() {
            private long i = primeiro;
            private Registro sobra;
            private Registro proximo = buscar();

            private Registro buscar() {
                while (true) {
                    if (sobra != null) {
                        Registro s = sobra;
                        sobra = null;
                        if (faixa.contem(s.id())) {
                            return s;
                        }
                        continue;
                    }
                    if (i >= registros) {
                        return null;
                    }
                    long atual = i++;
                    UUID id = new UUID(atual * passo, misturar(atual));
                    if (faixa.fim() != null && OrdemDosIds.comparar(id, faixa.fim()) >= 0) {
                        return null;
                    }
                    Registro registro = gerar(atual, id);
                    if (lado == Lado.RAZAO && sorteio(atual, 2) < sobrasPorMilhao) {
                        // Um lançamento a mais, logo depois do registro i: ninguém no Pix o conhece.
                        sobra = new Registro(new UUID(atual * passo + 1, misturar(~atual)), Registro.Tipo.PIX_ENVIO,
                                1 + Math.floorMod(misturar(~atual), 500_000L));
                    }
                    if (registro != null && faixa.contem(id)) {
                        return registro;
                    }
                }
            }

            @Override
            public boolean hasNext() {
                return proximo != null;
            }

            @Override
            public Registro next() {
                if (proximo == null) {
                    throw new NoSuchElementException();
                }
                Registro r = proximo;
                proximo = buscar();
                return r;
            }
        };
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(registrosDaFaixa, Spliterator.ORDERED | Spliterator.NONNULL), false);
    }

    /** O registro {@code i} deste lado; {@code null} se ele falta no razão. */
    private Registro gerar(long i, UUID id) {
        Registro.Tipo tipo = Registro.Tipo.values()[(int) Math.floorMod(i, 3L)];
        long centavos = 1 + Math.floorMod(misturar(i ^ 0x5DEECE66DL), 500_000L);
        if (lado == Lado.PIX) {
            return new Registro(id, tipo, centavos);
        }
        int sorteio = sorteio(i, 1);
        if (sorteio < faltasPorMilhao) {
            return null;
        }
        if (sorteio < faltasPorMilhao + divergentesPorMilhao) {
            return new Registro(id, tipo, centavos + 1);
        }
        return new Registro(id, tipo, centavos);
    }

    private static int sorteio(long i, long sal) {
        return (int) Math.floorMod(misturar(i * 31 + sal), (long) MILHAO);
    }

    /** O misturador do SplitMix64: espalha os bits de {@code i}, sempre do mesmo jeito. */
    static long misturar(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
