package io.github.alanjoffre.ledgerly.contas.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("CPF")
class CpfTest {

    @RepeatedTest(200)
    @DisplayName("todo CPF gerado com dígitos corretos é aceito, com ou sem máscara")
    void aceitaValidos() {
        Cpf cpf = new Cpf(CpfsDeTeste.gerar());
        assertThat(Cpf.de(cpf.formatado())).isEqualTo(cpf);
        assertThat(Cpf.de(cpf.numero())).isEqualTo(cpf);
    }

    @Test
    @DisplayName("qualquer dígito verificador trocado é recusado")
    void recusaDigitoTrocado() {
        String valido = CpfsDeTeste.gerar();
        for (int posicao = 9; posicao <= 10; posicao++) {
            int certo = valido.charAt(posicao) - '0';
            for (int errado = 0; errado <= 9; errado++) {
                if (errado == certo) {
                    continue;
                }
                String adulterado = valido.substring(0, posicao) + errado + valido.substring(posicao + 1);
                assertThatThrownBy(() -> new Cpf(adulterado))
                        .isInstanceOf(CpfInvalidoException.class)
                        .hasMessageContaining("dígitos verificadores");
            }
        }
    }

    @ParameterizedTest
    @DisplayName("formato inválido é recusado")
    @ValueSource(strings = {"", "123", "1234567890a", "123.456.789/09", "12345678901234"})
    void recusaFormato(String texto) {
        assertThatThrownBy(() -> Cpf.de(texto)).isInstanceOf(CpfInvalidoException.class);
    }

    @Test
    @DisplayName("todos os dígitos iguais é recusado, mesmo passando na conta")
    void recusaDigitosIguais() {
        assertThatThrownBy(() -> new Cpf("11111111111"))
                .isInstanceOf(CpfInvalidoException.class)
                .hasMessageContaining("iguais");
        assertThatThrownBy(() -> new Cpf("1234567890a")).isInstanceOf(CpfInvalidoException.class);
    }

    @Test
    @DisplayName("formatado, mascarado e toString (que mascara, para nunca vazar em log)")
    void formatos() {
        Cpf cpf = new Cpf(CpfsDeTeste.gerar());
        String n = cpf.numero();
        assertThat(cpf.formatado()).isEqualTo(n.substring(0, 3) + "." + n.substring(3, 6) + "." + n.substring(6, 9) + "-" + n.substring(9));
        assertThat(cpf.mascarado()).isEqualTo("***." + n.substring(3, 6) + "." + n.substring(6, 9) + "-**");
        assertThat(cpf).hasToString(cpf.mascarado());
    }
}
