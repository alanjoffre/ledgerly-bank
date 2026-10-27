package io.github.alanjoffre.ledgerly.extrato.dominio;

import java.io.Serial;

/**
 * O extrato não tem nada desta conta: ela não existe, ou existe e ainda não teve movimento que
 * chegasse até aqui (o extrato só conhece contas pelos lançamentos).
 */
public class ContaNaoEncontradaException extends RegraDeNegocioException {

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
