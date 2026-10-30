package io.github.alanjoffre.ledgerly.contas.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("Número da conta e cliente")
class NumeroContaEClienteTest {

    @ParameterizedTest(name = "{0} → dígito {1}")
    @DisplayName("dígito verificador módulo 11")
    @CsvSource({"100001, 2", "1, 9", "10, 8", "100002, 0", "6, 0"})
    void digito(int numero, int digito) {
        // Ex.: 100001 → da direita: 1×2 + 0×3 + 0×4 + 0×5 + 0×6 + 1×7 = 9; 11 - (9 % 11) = 2.
        // 100002 → 2×2 + 1×7 = 11; 11 - (11 % 11) = 11, que vira 0.
        assertThat(NumeroConta.novo(numero).digito()).isEqualTo(digito);
    }

    @Test
    @DisplayName("recusa dígito errado, agência fora do formato e número não positivo")
    void recusaInvalidos() {
        int certo = NumeroConta.calcularDigito(100001);
        assertThatThrownBy(() -> new NumeroConta("0001", 100001, (certo + 1) % 10))
                .isInstanceOf(DadoInvalidoException.class);
        assertThatThrownBy(() -> new NumeroConta("1", 100001, certo)).isInstanceOf(DadoInvalidoException.class);
        assertThatThrownBy(() -> new NumeroConta(null, 100001, certo)).isInstanceOf(DadoInvalidoException.class);
        assertThatThrownBy(() -> NumeroConta.novo(0)).isInstanceOf(DadoInvalidoException.class);
    }

    @Test
    @DisplayName("formato de exibição: 0001 / 100001-d")
    void exibicao() {
        NumeroConta n = NumeroConta.novo(100001);
        assertThat(n).hasToString("0001 / 100001-" + n.digito());
    }

    @Test
    @DisplayName("cliente: nome com espaços sobrando é normalizado; curto ou longo demais é recusado")
    void cliente() {
        Cpf cpf = new Cpf(CpfsDeTeste.gerar());
        assertThat(Cliente.cadastrar("  Maria   Exemplo  ", cpf, Instant.EPOCH).nome()).isEqualTo("Maria Exemplo");
        assertThat(Cliente.cadastrar("Mo", cpf, Instant.EPOCH).nome()).isEqualTo("Mo");
        assertThat(Cliente.cadastrar("x".repeat(120), cpf, Instant.EPOCH).nome()).hasSize(120);
        assertThatThrownBy(() -> Cliente.cadastrar("M", cpf, Instant.EPOCH)).isInstanceOf(DadoInvalidoException.class);
        assertThatThrownBy(() -> Cliente.cadastrar("x".repeat(121), cpf, Instant.EPOCH))
                .isInstanceOf(DadoInvalidoException.class);
    }
}
