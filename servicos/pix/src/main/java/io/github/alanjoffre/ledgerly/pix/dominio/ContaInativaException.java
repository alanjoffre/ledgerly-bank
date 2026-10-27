package io.github.alanjoffre.ledgerly.pix.dominio;

import java.io.Serial;

/** A conta está bloqueada ou encerrada. */
public class ContaInativaException extends RegraDeNegocioException {

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
