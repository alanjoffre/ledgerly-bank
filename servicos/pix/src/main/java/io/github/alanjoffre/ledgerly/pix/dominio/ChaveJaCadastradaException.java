package io.github.alanjoffre.ledgerly.pix.dominio;

import java.io.Serial;

/** A chave já pertence a uma conta (cada chave aponta para uma conta só). */
public class ChaveJaCadastradaException extends RegraDeNegocioException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ChaveJaCadastradaException(String mensagem) {
        super(mensagem);
    }

    @Override
    public String codigo() {
        return "chave-ja-cadastrada";
    }
}
