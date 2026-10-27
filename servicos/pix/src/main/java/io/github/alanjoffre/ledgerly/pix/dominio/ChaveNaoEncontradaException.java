package io.github.alanjoffre.ledgerly.pix.dominio;

import java.io.Serial;

/** Nenhuma conta tem esta chave Pix. */
public class ChaveNaoEncontradaException extends RegraDeNegocioException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ChaveNaoEncontradaException(String mensagem) {
        super(mensagem);
    }

    @Override
    public String codigo() {
        return "chave-nao-encontrada";
    }
}
