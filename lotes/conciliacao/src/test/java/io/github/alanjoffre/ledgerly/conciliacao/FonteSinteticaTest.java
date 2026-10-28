package io.github.alanjoffre.ledgerly.conciliacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Base sintética de lançamentos")
class FonteSinteticaTest {

    private static List<Registro> todos(Fonte fonte, int partes) {
        return Faixa.dividir(partes).stream().flatMap(f -> {
            try (Stream<Registro> s = fonte.ler(f)) {
                return s.toList().stream();
            }
        }).toList();
    }

    @Test
    @DisplayName("os ids saem em ordem crescente (a do banco) e sem repetição, em qualquer divisão em faixas")
    void ordem() {
        var razao = new FonteSintetica(50_000, FonteSintetica.Lado.RAZAO, 1000, 1000, 1000);
        List<Registro> numaFaixa = todos(razao, 1);
        for (int i = 1; i < numaFaixa.size(); i++) {
            assertThat(OrdemDosIds.comparar(numaFaixa.get(i - 1).id(), numaFaixa.get(i).id())).isNegative();
        }
        assertThat(todos(razao, 7)).isEqualTo(numaFaixa);
        assertThat(todos(razao, 64)).isEqualTo(numaFaixa);
    }

    @Test
    @DisplayName("o Pix tem exatamente os registros pedidos, espalhados pelo espaço inteiro de ids")
    void pix() {
        var pix = new FonteSintetica(10_000, FonteSintetica.Lado.PIX, 0, 0, 0);
        List<Registro> registros = todos(pix, 3);
        assertThat(registros).hasSize(10_000);
        assertThat(registros.getLast().id().getMostSignificantBits()).isNegative(); // passou da metade (sem sinal)
        assertThat(registros).extracting(Registro::tipo).containsOnly(Registro.Tipo.values());
    }

    @Test
    @DisplayName("sempre os mesmos dados: duas leituras dão o mesmo resultado")
    void deterministica() {
        Fonte[] a = FonteSintetica.par(5_000, 100, 100, 100);
        Fonte[] b = FonteSintetica.par(5_000, 100, 100, 100);
        assertThat(todos(a[1], 4)).isEqualTo(todos(b[1], 4));
    }

    @Test
    @DisplayName("parâmetros inválidos: recusados")
    void invalidos() {
        assertThatThrownBy(() -> new FonteSintetica(0, FonteSintetica.Lado.PIX, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FonteSintetica(10, FonteSintetica.Lado.RAZAO, -1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FonteSintetica(10, FonteSintetica.Lado.RAZAO, 600_000, 0, 600_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FonteSintetica(10, FonteSintetica.Lado.RAZAO, 0, 1_000_001, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Registro(UUID.randomUUID(), Registro.Tipo.PIX_ENVIO, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
