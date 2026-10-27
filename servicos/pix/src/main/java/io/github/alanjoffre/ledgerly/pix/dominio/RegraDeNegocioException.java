package io.github.alanjoffre.ledgerly.pix.dominio;

import java.io.Serial;

/**
 * Base das exceções de regra de negócio do Pix.
 *
 * <p>Cada subclasse tem um {@link #codigo()} estável (por exemplo {@code chave-ja-cadastrada}), que
 * a API devolve no campo {@code type} da resposta de erro. Quem consome decide pelo código, nunca
 * pelo texto.
 */
public abstract class RegraDeNegocioException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    protected RegraDeNegocioException(String mensagem) {
        super(mensagem);
    }

    public abstract String codigo();
}
