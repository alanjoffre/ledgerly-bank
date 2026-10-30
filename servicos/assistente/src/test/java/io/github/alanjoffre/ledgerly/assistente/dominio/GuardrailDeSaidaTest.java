package io.github.alanjoffre.ledgerly.assistente.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Trava de saída")
class GuardrailDeSaidaTest {

    private static final List<Consulta> SALDO = List.of(new Consulta("saldo", "Saldo da conta 12345-6: R$ 1.234,56."));
    private static final List<Trecho> AJUDA = List.of(new Trecho("pix-retido", "Pix retido para análise",
            "Pix de até R$ 500,00 segue normalmente; acima de R$ 500,00, fica retido."));

    @Test
    @DisplayName("valor que veio da ferramenta passa, escrito de qualquer jeito brasileiro")
    void valorDaFerramenta() {
        assertThat(GuardrailDeSaida.problema("Seu saldo é R$ 1.234,56.", SALDO, List.of())).isEmpty();
        assertThat(GuardrailDeSaida.problema("Seu saldo é de 1.234,56 reais.", SALDO, List.of())).isEmpty();
        assertThat(GuardrailDeSaida.problema("Seu saldo é R$1234,56.", SALDO, List.of())).isEmpty();
    }

    @Test
    @DisplayName("valor que veio do artigo de ajuda passa; \"R$ 500\" é o mesmo que R$ 500,00")
    void valorDaAjuda() {
        assertThat(GuardrailDeSaida.problema("Acima de R$ 500 o Pix fica retido.", List.of(), AJUDA)).isEmpty();
    }

    @Test
    @DisplayName("valor inventado é barrado")
    void valorInventado() {
        assertThat(GuardrailDeSaida.problema("Seu saldo é R$ 1.234,50.", SALDO, AJUDA))
                .contains("o valor R$ 1.234,50 não veio do extrato nem da ajuda");
        assertThat(GuardrailDeSaida.problema("Você gastou 80,00 no mercado.", SALDO, AJUDA)).isPresent();
    }

    @Test
    @DisplayName("número que não é dinheiro não é conferido: datas, contagens, número da conta")
    void naoEhDinheiro() {
        assertThat(GuardrailDeSaida.problema(
                "Foram 3 lançamentos na conta 12345-6 entre 2026-10-01 e 2026-10-28.", List.of(), List.of())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "O Pix foi para maria.souza@exemplo.test.",
        "A chave é 123e4567-e89b-42d3-a456-426614174000.",
        "O CPF dela é 000.000.000-00.",
        "Ligue para +55 11 91234-5678.",
        "O telefone é (11) 91234-5678.",
    })
    @DisplayName("chave Pix, documento ou telefone inteiro é barrado")
    void dadoPessoal(String resposta) {
        assertThat(GuardrailDeSaida.problema(resposta, SALDO, AJUDA)).contains("a resposta traz uma chave Pix, um documento ou um telefone");
    }

    @Test
    @DisplayName("vazia ou longa demais é barrada")
    void tamanho() {
        assertThat(GuardrailDeSaida.problema(" ", SALDO, AJUDA)).contains("resposta vazia");
        assertThat(GuardrailDeSaida.problema("a".repeat(1201), SALDO, AJUDA)).contains("resposta com 1201 caracteres (máximo 1200)");
    }

    @Test
    @DisplayName("Reais: escreve e lê no formato brasileiro")
    void reais() {
        assertThat(Reais.formatar(new BigDecimal("1234.5"))).isEqualTo("R$ 1.234,50");
        assertThat(Reais.valores("R$ 1.234,56, R$ 50, 9,90 e 3 Pix; conta 12345-6"))
                .containsExactly(new BigDecimal("1234.56"), new BigDecimal("50"), new BigDecimal("9.90"));
    }
}
