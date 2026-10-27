package io.github.alanjoffre.ledgerly.pix.dominio;

import java.io.Serial;

/** A transferência não existe. */
public class TransferenciaNaoEncontradaException extends RegraDeNegocioException {

    @Serial
    private static final long serialVersionUID = 1L;

    public TransferenciaNaoEncontradaException(String mensagem) {
        super(mensagem);
    }

    @Override
    public String codigo() {
        return "transferencia-nao-encontrada";
    }
}
