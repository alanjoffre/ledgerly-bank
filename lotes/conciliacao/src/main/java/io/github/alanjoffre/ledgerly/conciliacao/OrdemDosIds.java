package io.github.alanjoffre.ledgerly.conciliacao;

import java.util.Comparator;
import java.util.UUID;

/**
 * A ordem dos UUIDs que o PostgreSQL usa: byte a byte, sem sinal.
 *
 * <p><b>A armadilha:</b> o {@link UUID#compareTo} do Java compara os dois {@code long} do UUID
 * <i>com sinal</i>. Para ele, {@code ffffffff-...} vem ANTES de {@code 00000000-...} (o primeiro bit
 * ligado é o sinal de negativo). O banco ordena ao contrário. O merge join (ver {@link Conciliador})
 * caminha pelas duas listas na ordem em que o banco as entrega — com a comparação errada, ele
 * acharia "faltas" que não existem.
 */
public final class OrdemDosIds {

    /** Compara como o PostgreSQL: os 64 bits altos sem sinal e, empatando, os 64 baixos. */
    public static final Comparator<UUID> DO_BANCO = OrdemDosIds::comparar;

    private OrdemDosIds() {}

    public static int comparar(UUID a, UUID b) {
        int alta = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return alta != 0 ? alta : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }
}
