package io.github.alanjoffre.ledgerly.contas.dominio;

import java.io.Serial;

/** CPF com formato ou dígitos verificadores inválidos. */
public final class CpfInvalidoException extends RegraDeNegocioException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CpfInvalidoException(String mensagem) {
        super(mensagem);
    }

    @Override
    public String codigo() {
        return "cpf-invalido";
    }
}
