package io.github.alanjoffre.ledgerly.razao.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Chave de idempotência")
class ChaveIdempotenciaTest {

    @ParameterizedTest
    @DisplayName("aceita UUID e textos visíveis até 100 caracteres")
    @ValueSource(strings = {"3f2b8c1e-7a4d-4e2b-9c1a-5d6e7f8a9b0c", "pedido_42", "a"})
    void aceita(String valor) {
        assertThat(new ChaveIdempotencia(valor)).hasToString(valor);
    }

    @ParameterizedTest
    @DisplayName("recusa vazio, com espaço, com acento ou longo demais")
    @ValueSource(strings = {"", "   ", "com espaco", "ação", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void recusa(String valor) {
        assertThatThrownBy(() -> new ChaveIdempotencia(valor)).isInstanceOf(LancamentoInvalidoException.class);
    }
}
