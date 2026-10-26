package io.github.alanjoffre.ledgerly.contas.dominio;

import java.io.Serial;

/** Um dado de cadastro fora do formato esperado. */
public final class DadoInvalidoException extends RegraDeNegocioException {

    @Serial
    private static final long serialVersionUID = 1L;

    public DadoInvalidoException(String mensagem) {
        super(mensagem);
    }

    @Override
    public String codigo() {
        return "dado-invalido";
    }
}
