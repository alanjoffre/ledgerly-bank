package io.github.alanjoffre.ledgerly.contas.dominio;

import java.io.Serial;

/** A conta não pode ir da situação atual para a pedida. */
public final class TransicaoInvalidaException extends RegraDeNegocioException {

    @Serial
    private static final long serialVersionUID = 1L;

    public TransicaoInvalidaException(String mensagem) {
        super(mensagem);
    }

    @Override
    public String codigo() {
        return "transicao-invalida";
    }
}
