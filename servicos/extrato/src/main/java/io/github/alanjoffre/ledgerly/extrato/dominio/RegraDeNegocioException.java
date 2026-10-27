package io.github.alanjoffre.ledgerly.extrato.dominio;

import java.io.Serial;

/**
 * Base dos erros de negócio do extrato.
 *
 * <p>Cada subclasse tem um {@link #codigo()} estável, que a API devolve no campo {@code type} da
 * resposta de erro. Quem consome a API decide pelo código, nunca pelo texto da mensagem.
 */
public abstract class RegraDeNegocioException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    protected RegraDeNegocioException(String mensagem) {
        super(mensagem);
    }

    public abstract String codigo();
}
