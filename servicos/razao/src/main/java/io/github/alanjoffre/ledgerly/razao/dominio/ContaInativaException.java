package io.github.alanjoffre.ledgerly.razao.dominio;

import java.io.Serial;

/** A conta está bloqueada ou encerrada para esta operação. */
public final class ContaInativaException extends RegraDeNegocioException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ContaInativaException(String mensagem) {
        super(mensagem);
    }

    @Override
    public String codigo() {
        return "conta-inativa";
    }
}
