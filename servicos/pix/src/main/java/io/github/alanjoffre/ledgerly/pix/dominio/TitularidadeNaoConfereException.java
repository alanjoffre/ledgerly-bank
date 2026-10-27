package io.github.alanjoffre.ledgerly.pix.dominio;

import java.io.Serial;

/** A chave CPF não é do titular da conta. */
public class TitularidadeNaoConfereException extends RegraDeNegocioException {

    @Serial
    private static final long serialVersionUID = 1L;

    public TitularidadeNaoConfereException(String mensagem) {
        super(mensagem);
    }

    @Override
    public String codigo() {
        return "titularidade-nao-confere";
    }
}
