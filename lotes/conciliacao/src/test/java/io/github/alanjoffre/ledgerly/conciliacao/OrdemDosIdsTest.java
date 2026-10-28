package io.github.alanjoffre.ledgerly.conciliacao;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Ordem dos ids: a do PostgreSQL, não a do UUID.compareTo")
class OrdemDosIdsTest {

    private static final UUID BAIXO = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ALTO = UUID.fromString("ffffffff-0000-0000-0000-000000000000");

    @Test
    @DisplayName("a armadilha: o compareTo do Java põe o id que começa com f antes do que começa com 0")
    void armadilha() {
        assertThat(ALTO.compareTo(BAIXO)).isNegative();
        assertThat(OrdemDosIds.comparar(ALTO, BAIXO)).isPositive();
    }

    @Test
    @DisplayName("igual à ordem do texto do id, que é a do banco (byte a byte, sem sinal)")
    void comoOTexto() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            ids.add(UUID.randomUUID());
        }
        ids.add(new UUID(0x8000000000000000L, 0x8000000000000000L));
        ids.add(new UUID(0x8000000000000000L, 1L));
        List<UUID> pelaOrdem = ids.stream().sorted(OrdemDosIds.DO_BANCO).toList();
        List<UUID> peloTexto = ids.stream().sorted((a, b) -> a.toString().compareTo(b.toString())).toList();
        assertThat(pelaOrdem).isEqualTo(peloTexto);
        assertThat(OrdemDosIds.comparar(BAIXO, BAIXO)).isZero();
    }
}
