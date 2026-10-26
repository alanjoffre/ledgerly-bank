package io.github.alanjoffre.ledgerly.razao.dominio;

import java.util.Objects;

/**
 * Identificador que o cliente da API escolhe para um pedido (cabeçalho {@code Idempotency-Key}).
 *
 * <p>Se o mesmo pedido chegar duas vezes — o usuário clicou duas vezes, a rede caiu e o app
 * reenviou —, a chave é a mesma, e o razão devolve o lançamento já feito em vez de fazer outro.
 * Ver {@code docs/adr/0010-idempotencia-pela-chave-no-banco.md}.
 *
 * @param valor de 1 a 100 caracteres visíveis (um UUID é o uso típico)
 */
public record ChaveIdempotencia(String valor) {

    public static final int TAMANHO_MAXIMO = 100;

    public ChaveIdempotencia {
        Objects.requireNonNull(valor, "a chave de idempotência é obrigatória");
        if (valor.isBlank() || valor.length() > TAMANHO_MAXIMO || !valor.chars().allMatch(c -> c > 32 && c < 127)) {
            throw new LancamentoInvalidoException(
                    "A chave de idempotência deve ter de 1 a %d caracteres visíveis, sem espaço"
                            .formatted(TAMANHO_MAXIMO));
        }
    }

    @Override
    public String toString() {
        return valor;
    }
}
