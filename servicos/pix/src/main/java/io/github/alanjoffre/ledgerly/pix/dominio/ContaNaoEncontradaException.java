package io.github.alanjoffre.ledgerly.pix.dominio;

import java.io.Serial;

/** A conta não existe. */
public class ContaNaoEncontradaException extends RegraDeNegocioException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ContaNaoEncontradaException(String mensagem) {
        super(mensagem);
    }

    @Override
    public String codigo() {
        return "conta-nao-encontrada";
    }
}
