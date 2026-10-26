package io.github.alanjoffre.ledgerly.razao.dominio;

import java.io.Serial;

/** Já existe uma conta com este id, com dados diferentes. */
public final class ContaJaExisteException extends RegraDeNegocioException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ContaJaExisteException(String mensagem) {
        super(mensagem);
    }

    @Override
    public String codigo() {
        return "conta-ja-existe";
    }
}
