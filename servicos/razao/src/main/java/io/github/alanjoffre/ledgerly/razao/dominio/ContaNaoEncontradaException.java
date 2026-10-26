package io.github.alanjoffre.ledgerly.razao.dominio;

import java.io.Serial;

/** Conta inexistente no razão. */
public final class ContaNaoEncontradaException extends RegraDeNegocioException {

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
