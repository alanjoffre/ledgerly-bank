package io.github.alanjoffre.ledgerly.razao.dominio;

import java.io.Serial;

/** A chave de idempotência já foi usada para um pedido DIFERENTE. */
public final class ChaveIdempotenciaReutilizadaException extends RegraDeNegocioException {

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
