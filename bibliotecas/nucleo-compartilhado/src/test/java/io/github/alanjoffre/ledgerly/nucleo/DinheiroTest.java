package io.github.alanjoffre.ledgerly.nucleo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Testes do {@link Dinheiro}. Cada {@code @Nested} agrupa uma regra de negócio, e o
 * {@code @DisplayName} diz a regra em português — o relatório de testes vira uma
 * especificação legível.
 */
@DisplayName("Dinheiro")
class DinheiroTest {

    private static final Currency DOLAR = Currency.getInstance("USD");
    private static final Currency IENE = Currency.getInstance("JPY");

    @Nested
    @DisplayName("criação")
    class Criacao {

        @Test
        @DisplayName("normaliza para as casas da moeda: 10, 10.0 e 10.00 são iguais")
        void normalizaEscala() {
            assertThat(Dinheiro.reais("10"))
                    .isEqualTo(Dinheiro.reais("10.0"))
                    .isEqualTo(Dinheiro.reais("10.00"));
            assertThat(Dinheiro.reais("10").valor()).isEqualByComparingTo("10.00");
            assertThat(Dinheiro.reais("10").valor().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("recusa valor com mais casas que a moeda, em vez de arredondar em silêncio")
        void recusaCasasDemais() {
            assertThatThrownBy(() -> Dinheiro.reais("10.005"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mais de 2 casas decimais");
        }

        @Test
        @DisplayName("aceita zeros extras à direita (10.500 é 10.50)")
        void aceitaZerosExtras() {
            assertThat(Dinheiro.reais("10.500")).isEqualTo(Dinheiro.reais("10.50"));
        }

        @Test
        @DisplayName("respeita a moeda: o iene não tem centavos")
        void ieneSemCentavos() {
            assertThat(Dinheiro.de(new BigDecimal("150"), IENE).valor().scale()).isZero();
            assertThatThrownBy(() -> Dinheiro.de(new BigDecimal("150.5"), IENE))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("exige valor e moeda")
        void exigeCampos() {
            assertThatThrownBy(() -> new Dinheiro(null, Dinheiro.REAL))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new Dinheiro(BigDecimal.ONE, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("zero é zero na moeda pedida")
        void zero() {
            assertThat(Dinheiro.zero(DOLAR).ehZero()).isTrue();
            assertThat(Dinheiro.zero(DOLAR).moeda()).isEqualTo(DOLAR);
        }
    }

    @Nested
    @DisplayName("aritmética")
    class Aritmetica {

        @Test
        @DisplayName("0,10 + 0,20 é exatamente 0,30 (com double daria 0,30000000000000004)")
        void semErroDeDouble() {
            assertThat(0.1 + 0.2).isNotEqualTo(0.3); // o problema que o Dinheiro resolve
            assertThat(Dinheiro.reais("0.10").somar(Dinheiro.reais("0.20")))
                    .isEqualTo(Dinheiro.reais("0.30"));
        }

        @Test
        @DisplayName("subtrai e pode ficar negativo (quem proíbe saldo negativo é a conta, não o dinheiro)")
        void subtrai() {
            Dinheiro resultado = Dinheiro.reais("10.00").subtrair(Dinheiro.reais("15.50"));
            assertThat(resultado).isEqualTo(Dinheiro.reais("-5.50"));
            assertThat(resultado.ehNegativo()).isTrue();
        }

        @Test
        @DisplayName("negar inverte o sinal")
        void negar() {
            assertThat(Dinheiro.reais("7.25").negar()).isEqualTo(Dinheiro.reais("-7.25"));
        }

        @ParameterizedTest(name = "{0} × {1} = {2}")
        @DisplayName("multiplicar usa arredondamento bancário (meio para o par)")
        @CsvSource({
            "100.00, 0.02345, 2.34", // 2,345 → 2,34 (o 4 já é par)
            "100.00, 0.02355, 2.36", // 2,355 → 2,36 (sobe para o par)
            "10.00,  1.5,     15.00",
            "0.01,   0.5,     0.00", // 0,005 → 0,00 (o 0 é par)
        })
        void multiplicaComArredondamentoBancario(String valor, String fator, String esperado) {
            assertThat(Dinheiro.reais(valor).multiplicar(new BigDecimal(fator)))
                    .isEqualTo(Dinheiro.reais(esperado));
        }

        @Test
        @DisplayName("é imutável: operar não altera o original")
        void imutavel() {
            Dinheiro original = Dinheiro.reais("10.00");
            original.somar(Dinheiro.reais("5.00"));
            assertThat(original).isEqualTo(Dinheiro.reais("10.00"));
        }
    }

    @Nested
    @DisplayName("moedas diferentes")
    class MoedasDiferentes {

        @Test
        @DisplayName("não soma real com dólar")
        void naoSoma() {
            Dinheiro dolares = Dinheiro.de(BigDecimal.TEN, DOLAR);
            assertThatThrownBy(() -> Dinheiro.reais("10").somar(dolares))
                    .isInstanceOf(MoedasDiferentesException.class)
                    .hasMessage("Moedas diferentes: esperado BRL, recebido USD");
        }

        @Test
        @DisplayName("não subtrai nem compara real com dólar")
        void naoSubtraiNemCompara() {
            Dinheiro dolares = Dinheiro.de(BigDecimal.TEN, DOLAR);
            assertThatThrownBy(() -> Dinheiro.reais("10").subtrair(dolares))
                    .isInstanceOf(MoedasDiferentesException.class);
            assertThatThrownBy(() -> Dinheiro.reais("10").ehMaiorQue(dolares))
                    .isInstanceOf(MoedasDiferentesException.class);
        }

        @Test
        @DisplayName("10 reais e 10 dólares não são iguais")
        void naoSaoIguais() {
            assertThat(Dinheiro.reais("10")).isNotEqualTo(Dinheiro.de(BigDecimal.TEN, DOLAR));
        }
    }

    @Nested
    @DisplayName("rateio")
    class Rateio {

        @Test
        @DisplayName("R$ 100,00 em 3 partes dá 33,34 + 33,33 + 33,33 — sem perder o centavo")
        void rateioClassico() {
            assertThat(Dinheiro.reais("100.00").ratear(3)).containsExactly(
                    Dinheiro.reais("33.34"), Dinheiro.reais("33.33"), Dinheiro.reais("33.33"));
        }

        @Test
        @DisplayName("valor negativo também fecha: -R$ 100,00 em 3 partes")
        void rateioNegativo() {
            assertThat(Dinheiro.reais("-100.00").ratear(3)).containsExactly(
                    Dinheiro.reais("-33.34"), Dinheiro.reais("-33.33"), Dinheiro.reais("-33.33"));
        }

        @Test
        @DisplayName("divisão exata não precisa de ajuste")
        void divisaoExata() {
            assertThat(Dinheiro.reais("9.00").ratear(3)).containsOnly(Dinheiro.reais("3.00"));
        }

        @Test
        @DisplayName("1 centavo em 3 partes: uma recebe o centavo, as outras zero")
        void umCentavo() {
            assertThat(Dinheiro.reais("0.01").ratear(3)).containsExactly(
                    Dinheiro.reais("0.01"), Dinheiro.reais("0.00"), Dinheiro.reais("0.00"));
        }

        @ParameterizedTest
        @DisplayName("recusa número de partes zero ou negativo")
        @ValueSource(ints = {0, -1})
        void recusaPartesInvalidas(int partes) {
            assertThatThrownBy(() -> Dinheiro.reais("10").ratear(partes))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /**
         * Teste de propriedade (<i>property-based testing</i>): em vez de um exemplo,
         * verifica uma regra que vale para QUALQUER entrada — aqui, 10 mil
         * combinações aleatórias (com semente fixa, para o teste ser reprodutível).
         */
        @Test
        @DisplayName("propriedade: para qualquer valor e qualquer nº de partes, a soma do rateio é o original")
        void somaDoRateioEhSempreOOriginal() {
            Random aleatorio = new Random(42);
            for (int i = 0; i < 10_000; i++) {
                Dinheiro original = Dinheiro.de(
                        BigDecimal.valueOf(aleatorio.nextLong(-10_000_000, 10_000_000), 2), Dinheiro.REAL);
                int partes = aleatorio.nextInt(1, 50);

                List<Dinheiro> pedacos = original.ratear(partes);

                assertThat(pedacos).hasSize(partes);
                assertThat(pedacos.stream().reduce(Dinheiro.zero(Dinheiro.REAL), Dinheiro::somar))
                        .as("rateio de %s em %d partes", original, partes)
                        .isEqualTo(original);
                // Nenhum pedaço difere de outro em mais de 1 centavo.
                BigDecimal maior = pedacos.stream().map(Dinheiro::valor).max(BigDecimal::compareTo).orElseThrow();
                BigDecimal menor = pedacos.stream().map(Dinheiro::valor).min(BigDecimal::compareTo).orElseThrow();
                assertThat(maior.subtract(menor)).isLessThanOrEqualTo(new BigDecimal("0.01"));
            }
        }
    }

    @Nested
    @DisplayName("comparação e exibição")
    class ComparacaoEExibicao {

        @Test
        @DisplayName("compara pelo valor")
        void compara() {
            Dinheiro dez = Dinheiro.reais("10");
            Dinheiro vinte = Dinheiro.reais("20");
            assertThat(vinte.ehMaiorQue(dez)).isTrue();
            assertThat(dez.ehMenorQue(vinte)).isTrue();
            assertThat(dez.compareTo(Dinheiro.reais("10.00"))).isZero();
        }

        @Test
        @DisplayName("sinal: positivo, negativo e zero")
        void sinal() {
            assertThat(Dinheiro.reais("1").ehPositivo()).isTrue();
            assertThat(Dinheiro.reais("-1").ehPositivo()).isFalse();
            assertThat(Dinheiro.reais("0").ehNegativo()).isFalse();
        }

        @Test
        @DisplayName("formata para o cliente em pt-BR (R$ 1.234,56)")
        void formataPtBr() {
            // O Java usa espaço não separável (U+00A0) entre "R$" e o número.
            assertThat(Dinheiro.reais("1234.56").formatar(Locale.of("pt", "BR")))
                    .isEqualTo("R$ 1.234,56");
        }

        @Test
        @DisplayName("toString técnico, para log")
        void toStringTecnico() {
            assertThat(Dinheiro.reais("10.5")).hasToString("BRL 10.50");
        }
    }
}
