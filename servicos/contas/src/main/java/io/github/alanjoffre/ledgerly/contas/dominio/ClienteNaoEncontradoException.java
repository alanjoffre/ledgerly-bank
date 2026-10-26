package io.github.alanjoffre.ledgerly.contas.dominio;

import java.io.Serial;

/** Cliente inexistente. */
public final class ClienteNaoEncontradoException extends RegraDeNegocioException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ClienteNaoEncontradoException(String mensagem) {
        super(mensagem);
    }

    @Override
    public String codigo() {
        return "cliente-nao-encontrado";
    }
}
