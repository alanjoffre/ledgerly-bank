package io.github.alanjoffre.ledgerly.razao.dominio;

import java.io.Serial;

/** Operação que a regra de negócio não permite, por exemplo movimentar a conta interna Caixa pela API. */
public final class OperacaoNaoPermitidaException extends RegraDeNegocioException {

    @Serial
    private static final long serialVersionUID = 1L;

    public OperacaoNaoPermitidaException(String mensagem) {
        super(mensagem);
    }

    @Override
    public String codigo() {
        return "operacao-nao-permitida";
    }
}
