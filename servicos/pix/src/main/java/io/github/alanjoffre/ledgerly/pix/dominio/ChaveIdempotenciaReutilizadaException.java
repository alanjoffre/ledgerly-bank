package io.github.alanjoffre.ledgerly.pix.dominio;

import java.io.Serial;

/** A chave de idempotência já foi usada num pedido diferente. */
public class ChaveIdempotenciaReutilizadaException extends RegraDeNegocioException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ChaveIdempotenciaReutilizadaException(String mensagem) {
        super(mensagem);
    }

    @Override
    public String codigo() {
        return "chave-idempotencia-reutilizada";
    }
}
