package io.github.alanjoffre.ledgerly.pix.dominio;

import java.io.Serial;

/** A chave Pix não segue o formato do tipo (CPF, e-mail, telefone ou aleatória). */
public class ChaveInvalidaException extends RegraDeNegocioException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ChaveInvalidaException(String mensagem) {
        super(mensagem);
    }

    @Override
    public String codigo() {
        return "chave-invalida";
    }
}
