package io.github.alanjoffre.ledgerly.conciliacao;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * O resultado de uma conciliação: quantos ids foram conferidos, quantos bateram e as divergências.
 *
 * <p>As divergências vão contadas por tipo e, no máximo {@link #LIMITE_DA_AMOSTRA} delas, por
 * extenso — numa base com milhões de registros, um erro de carga poderia gerar milhões de
 * divergências, e o relatório não pode estourar a memória por isso.
 *
 * @param conferidos   quantos ids distintos foram vistos, somando os dois lados
 * @param conciliados  quantos estão iguais nos dois lados
 * @param divergencias quantas divergências de cada tipo
 * @param amostra      as primeiras divergências, na ordem dos ids
 */
public record Resultado(
        long conferidos, long conciliados, Map<Divergencia.Tipo, Long> divergencias, List<Divergencia> amostra) {

    public static final int LIMITE_DA_AMOSTRA = 100;

    public static final Resultado VAZIO = new Resultado(0, 0, Map.of(), List.of());

    public Resultado {
        divergencias = Map.copyOf(divergencias);
        amostra = List.copyOf(amostra);
    }

    public long divergencias(Divergencia.Tipo tipo) {
        return divergencias.getOrDefault(tipo, 0L);
    }

    public long totalDeDivergencias() {
        return divergencias.values().stream().mapToLong(Long::longValue).sum();
    }

    /** Os dois lados batem por inteiro. */
    public boolean bate() {
        return totalDeDivergencias() == 0;
    }

    /** Junta o resultado de duas faixas; {@code outro} vem depois desta na ordem dos ids. */
    public Resultado somar(Resultado outro) {
        Map<Divergencia.Tipo, Long> soma = new EnumMap<>(Divergencia.Tipo.class);
        divergencias.forEach((tipo, n) -> soma.merge(tipo, n, Long::sum));
        outro.divergencias.forEach((tipo, n) -> soma.merge(tipo, n, Long::sum));
        List<Divergencia> juntas = new ArrayList<>(amostra);
        juntas.addAll(outro.amostra.subList(0, Math.clamp(LIMITE_DA_AMOSTRA - amostra.size(), 0, outro.amostra.size())));
        return new Resultado(conferidos + outro.conferidos, conciliados + outro.conciliados, soma, juntas);
    }
}
