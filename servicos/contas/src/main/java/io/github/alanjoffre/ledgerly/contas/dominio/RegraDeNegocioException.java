package io.github.alanjoffre.ledgerly.contas.dominio;

import java.io.Serial;

/**
 * Base das exceções de regra de negócio do serviço de contas. O {@link #codigo()} é estável e vai
 * no campo {@code type} da resposta de erro da API.
 *
 * <p>É uma classe própria deste serviço, não a do razão: cada serviço é um contexto delimitado
 * (<i>bounded context</i>, no DDD) e evolui as suas regras sem depender do outro.
 */
public abstract class RegraDeNegocioException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    protected RegraDeNegocioException(String mensagem) {
        super(mensagem);
    }

    public abstract String codigo();
}
