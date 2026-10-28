package io.github.alanjoffre.ledgerly.conciliacao;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Um pedaço do espaço de ids, de {@code inicio} (incluído) até {@code fim} (excluído).
 *
 * <p><b>Particionamento:</b> dividir o trabalho em faixas que não se sobrepõem, para cada uma ser
 * conciliada sozinha, em paralelo. Como o id do lançamento é um UUID aleatório, faixas de mesmo
 * tamanho recebem quase a mesma quantidade de lançamentos. No banco, cada faixa vira um
 * {@code WHERE id >= :inicio AND id < :fim ORDER BY id}, que usa o índice da chave primária.
 *
 * @param inicio o primeiro id da faixa
 * @param fim    o primeiro id da próxima faixa; {@code null} na última (vai até o fim)
 */
public record Faixa(UUID inicio, UUID fim) {

    private static final UUID MENOR = new UUID(0L, 0L);

    public Faixa {
        if (inicio == null) {
            throw new IllegalArgumentException("A faixa precisa de um início");
        }
        if (fim != null && OrdemDosIds.comparar(inicio, fim) >= 0) {
            throw new IllegalArgumentException("Faixa vazia: %s até %s".formatted(inicio, fim));
        }
    }

    /** O espaço inteiro de ids, numa faixa só. */
    public static Faixa tudo() {
        return new Faixa(MENOR, null);
    }

    /** Divide o espaço inteiro em {@code partes} faixas do mesmo tamanho, na ordem. */
    public static List<Faixa> dividir(int partes) {
        if (partes < 1 || partes > 4096) {
            throw new IllegalArgumentException("O número de partes deve ficar entre 1 e 4096: " + partes);
        }
        // 2^64 / partes, sem sinal: o tamanho de cada faixa nos 64 bits altos do UUID.
        long tamanho = Long.divideUnsigned(-1L, partes);
        List<Faixa> faixas = new ArrayList<>(partes);
        UUID inicio = MENOR;
        for (int k = 1; k < partes; k++) {
            UUID fim = new UUID(tamanho * k, 0L);
            faixas.add(new Faixa(inicio, fim));
            inicio = fim;
        }
        faixas.add(new Faixa(inicio, null));
        return faixas;
    }

    public boolean contem(UUID id) {
        return OrdemDosIds.comparar(id, inicio) >= 0 && (fim == null || OrdemDosIds.comparar(id, fim) < 0);
    }
}
