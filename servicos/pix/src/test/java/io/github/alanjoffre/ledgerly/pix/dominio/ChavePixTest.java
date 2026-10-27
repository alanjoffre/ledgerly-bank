package io.github.alanjoffre.ledgerly.pix.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Chave Pix")
class ChavePixTest {

    @Nested
    @DisplayName("CPF")
    class TipoCpf {

        @RepeatedTest(value = 100, name = "CPF gerado {currentRepetition}: aceito com e sem pontuação")
        void valido() {
            String cpf = CpfsDeTeste.gerar();
            String formatado = "%s.%s.%s-%s".formatted(cpf.substring(0, 3), cpf.substring(3, 6), cpf.substring(6, 9), cpf.substring(9));
            assertThat(new ChavePix(TipoChave.CPF, formatado).valor()).isEqualTo(cpf);
            assertThat(new ChavePix(TipoChave.CPF, cpf)).isEqualTo(new ChavePix(TipoChave.CPF, formatado));
        }

        @Test
        @DisplayName("dígito verificador errado, tamanho errado ou todos iguais: recusado")
        void invalido() {
            String cpf = CpfsDeTeste.gerar();
            assertThatThrownBy(() -> new ChavePix(TipoChave.CPF, CpfsDeTeste.comDigitoErrado(cpf)))
                    .isInstanceOf(ChaveInvalidaException.class);
            assertThatThrownBy(() -> new ChavePix(TipoChave.CPF, cpf.substring(1))).isInstanceOf(ChaveInvalidaException.class);
            assertThatThrownBy(() -> new ChavePix(TipoChave.CPF, "11111111111")).isInstanceOf(ChaveInvalidaException.class);
        }

        @Test
        @DisplayName("mascarado como no app do banco: ***.456.789-**")
        void mascarado() {
            String cpf = CpfsDeTeste.gerar();
            assertThat(new ChavePix(TipoChave.CPF, cpf).mascarada())
                    .isEqualTo("***.%s.%s-**".formatted(cpf.substring(3, 6), cpf.substring(6, 9)));
        }
    }

    @Test
    @DisplayName("e-mail: guardado em minúsculas, mascarado, até 77 caracteres")
    void email() {
        ChavePix chave = new ChavePix(TipoChave.EMAIL, "  Maria.Souza@Exemplo.com ");
        assertThat(chave.valor()).isEqualTo("maria.souza@exemplo.com");
        assertThat(chave.mascarada()).isEqualTo("m***@exemplo.com");
        assertThatThrownBy(() -> new ChavePix(TipoChave.EMAIL, "sem-arroba")).isInstanceOf(ChaveInvalidaException.class);
        assertThatThrownBy(() -> new ChavePix(TipoChave.EMAIL, "a".repeat(70) + "@exemplo.com"))
                .isInstanceOf(ChaveInvalidaException.class);
    }

    @Test
    @DisplayName("telefone: formato +55DDNNNNNNNNN, aceita espaços e parênteses ao digitar")
    void telefone() {
        ChavePix chave = new ChavePix(TipoChave.TELEFONE, "+55 (11) 98765-4321");
        assertThat(chave.valor()).isEqualTo("+5511987654321");
        assertThat(chave.mascarada()).isEqualTo("+55 (11) *****-4321");
        assertThat(new ChavePix(TipoChave.TELEFONE, "+551133334444").valor()).isEqualTo("+551133334444");
        assertThatThrownBy(() -> new ChavePix(TipoChave.TELEFONE, "11987654321")).isInstanceOf(ChaveInvalidaException.class);
        assertThatThrownBy(() -> new ChavePix(TipoChave.TELEFONE, "+1415555123")).isInstanceOf(ChaveInvalidaException.class);
    }

    @Test
    @DisplayName("aleatória: UUID versão 4 gerado pelo banco, mostrado inteiro")
    void aleatoria() {
        ChavePix chave = ChavePix.aleatoria();
        assertThat(UUID.fromString(chave.valor()).version()).isEqualTo(4);
        assertThat(chave.mascarada()).isEqualTo(chave.valor());
        assertThat(new ChavePix(TipoChave.ALEATORIA, chave.valor().toUpperCase())).isEqualTo(chave);
        assertThatThrownBy(() -> new ChavePix(TipoChave.ALEATORIA, "nao-e-uuid")).isInstanceOf(ChaveInvalidaException.class);
    }

    @Test
    @DisplayName("identificar: o tipo sai do formato, como quando o cliente digita a chave")
    void identificar() {
        String cpf = CpfsDeTeste.gerar();
        String evp = UUID.randomUUID().toString();
        assertThat(ChavePix.identificar(cpf).tipo()).isEqualTo(TipoChave.CPF);
        assertThat(ChavePix.identificar("m@exemplo.com").tipo()).isEqualTo(TipoChave.EMAIL);
        assertThat(ChavePix.identificar("+5511987654321").tipo()).isEqualTo(TipoChave.TELEFONE);
        assertThat(ChavePix.identificar(evp).tipo()).isEqualTo(TipoChave.ALEATORIA);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("vazia: recusada")
    void vazia(String valor) {
        assertThatThrownBy(() -> ChavePix.identificar(valor)).isInstanceOf(ChaveInvalidaException.class);
        assertThatThrownBy(() -> new ChavePix(TipoChave.EMAIL, valor)).isInstanceOf(ChaveInvalidaException.class);
    }
}
