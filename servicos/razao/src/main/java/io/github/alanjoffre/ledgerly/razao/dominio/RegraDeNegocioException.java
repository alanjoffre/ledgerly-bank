package io.github.alanjoffre.ledgerly.razao.dominio;

import java.io.Serial;

/**
 * Base das exceções de regra de negócio do razão.
 *
 * <p>Cada subclasse tem um {@link #codigo()} estável (por exemplo {@code saldo-insuficiente}),
 * que a API devolve no campo {@code type} da resposta de erro. Quem consome a API decide pelo
 * código, nunca pelo texto da mensagem — o texto pode mudar, o código não.
 */
public abstract class RegraDeNegocioException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    protected RegraDeNegocioException(String mensagem) {
        super(mensagem);
    }

    public abstract String codigo();
}
