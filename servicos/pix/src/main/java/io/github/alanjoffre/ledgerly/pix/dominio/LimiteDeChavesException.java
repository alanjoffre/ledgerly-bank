package io.github.alanjoffre.ledgerly.pix.dominio;

import java.io.Serial;

/** A conta já tem o máximo de chaves permitido. */
public class LimiteDeChavesException extends RegraDeNegocioException {

    @Serial
    private static final long serialVersionUID = 1L;

    public LimiteDeChavesException(String mensagem) {
        super(mensagem);
    }

    @Override
    public String codigo() {
        return "limite-de-chaves";
    }
}
