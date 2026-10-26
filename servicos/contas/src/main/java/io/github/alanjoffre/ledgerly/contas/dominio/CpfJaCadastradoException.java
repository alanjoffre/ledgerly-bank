package io.github.alanjoffre.ledgerly.contas.dominio;

import java.io.Serial;

/** Já existe cliente com este CPF. */
public final class CpfJaCadastradoException extends RegraDeNegocioException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CpfJaCadastradoException(String mensagem) {
        super(mensagem);
    }

    @Override
    public String codigo() {
        return "cpf-ja-cadastrado";
    }
}
