package io.github.alanjoffre.ledgerly.conciliacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Faixas de ids")
class FaixaTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 16, 100})
    @DisplayName("as faixas cobrem todos os ids, sem sobreposição: cada id cai em exatamente uma")
    void cobrem(int partes) {
        List<Faixa> faixas = Faixa.dividir(partes);
        assertThat(faixas).hasSize(partes);
        List<UUID> ids = new java.util.ArrayList<>(List.of(
                new UUID(0L, 0L), new UUID(-1L, -1L), new UUID(Long.MIN_VALUE, 0L), new UUID(Long.MAX_VALUE, -1L)));
        for (int i = 0; i < 2000; i++) {
            ids.add(UUID.randomUUID());
        }
        faixas.stream().skip(1).forEach(f -> ids.add(f.inicio())); // as bordas também
        for (UUID id : ids) {
            assertThat(faixas.stream().filter(f -> f.contem(id)).count()).as(id.toString()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("ids aleatórios se espalham quase por igual entre as faixas")
    void espalham() {
        List<Faixa> faixas = Faixa.dividir(16);
        int[] porFaixa = new int[16];
        for (int i = 0; i < 160_000; i++) {
            UUID id = UUID.randomUUID();
            for (int k = 0; k < 16; k++) {
                if (faixas.get(k).contem(id)) {
                    porFaixa[k]++;
                }
            }
        }
        assertThat(java.util.Arrays.stream(porFaixa).min().orElseThrow()).isGreaterThan(9_000);
        assertThat(java.util.Arrays.stream(porFaixa).max().orElseThrow()).isLessThan(11_000);
    }

    @Test
    @DisplayName("tudo(): uma faixa só; partes fora do intervalo e faixa vazia: recusadas")
    void limites() {
        assertThat(Faixa.tudo().contem(new UUID(-1L, -1L))).isTrue();
        assertThat(Faixa.dividir(1)).containsExactly(Faixa.tudo());
        assertThatThrownBy(() -> Faixa.dividir(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Faixa.dividir(4097)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Faixa(null, null)).isInstanceOf(IllegalArgumentException.class);
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new Faixa(id, id)).isInstanceOf(IllegalArgumentException.class);
    }
}
