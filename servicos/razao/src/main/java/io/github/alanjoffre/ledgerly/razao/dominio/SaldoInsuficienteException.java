package io.github.alanjoffre.ledgerly.razao.dominio;

import java.io.Serial;

/** Saldo insuficiente para o débito. */
public final class SaldoInsuficienteException extends RegraDeNegocioException {

    @Serial
    private static final long serialVersionUID = 1L;

    public SaldoInsuficienteException(String mensagem) {
        super(mensagem);
    }

    @Override
    public String codigo() {
        return "saldo-insuficiente";
    }
}
