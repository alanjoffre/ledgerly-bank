package io.github.alanjoffre.ledgerly.pix.dominio;

import java.io.Serial;

/** A conta da devolução não é do mesmo titular que pagou o Pix. */
public class ContaDeOutroTitularException extends RegraDeNegocioException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ContaDeOutroTitularException(String mensagem) {
        super(mensagem);
    }

    @Override
    public String codigo() {
        return "conta-de-outro-titular";
    }
}
