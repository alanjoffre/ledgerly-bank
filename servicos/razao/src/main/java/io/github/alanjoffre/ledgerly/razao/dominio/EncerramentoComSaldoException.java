package io.github.alanjoffre.ledgerly.razao.dominio;

import java.io.Serial;

/** A conta só pode ser encerrada com saldo zero. */
public final class EncerramentoComSaldoException extends RegraDeNegocioException {

    @Serial
    private static final long serialVersionUID = 1L;

    public EncerramentoComSaldoException(String mensagem) {
        super(mensagem);
    }

    @Override
    public String codigo() {
        return "encerramento-com-saldo";
    }
}
