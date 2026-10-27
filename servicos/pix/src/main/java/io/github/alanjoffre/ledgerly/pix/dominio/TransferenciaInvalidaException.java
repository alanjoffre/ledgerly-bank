package io.github.alanjoffre.ledgerly.pix.dominio;

import java.io.Serial;

/** O pedido de transferência quebra uma regra (valor, mesma conta, descrição...). */
public class TransferenciaInvalidaException extends RegraDeNegocioException {

    @Serial
    private static final long serialVersionUID = 1L;

    public TransferenciaInvalidaException(String mensagem) {
        super(mensagem);
    }

    @Override
    public String codigo() {
        return "transferencia-invalida";
    }
}
