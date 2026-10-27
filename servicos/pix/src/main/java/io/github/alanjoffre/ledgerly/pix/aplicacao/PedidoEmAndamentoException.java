package io.github.alanjoffre.ledgerly.pix.aplicacao;

import java.io.Serial;

/** Um pedido com a mesma chave de idempotência ainda está em andamento. Tente de novo em instantes. */
public class PedidoEmAndamentoException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public PedidoEmAndamentoException(String mensagem) {
        super(mensagem);
    }

    /** O {@code type} estável da resposta de erro. */
    public String codigo() {
        return "pedido-em-andamento";
    }
}
