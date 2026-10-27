package io.github.alanjoffre.ledgerly.pix.aplicacao;

import java.io.Serial;

/** Um serviço de que o Pix depende (contas) não respondeu; nada foi criado. Tente de novo. */
public class ServicoIndisponivelException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ServicoIndisponivelException(String mensagem) {
        super(mensagem);
    }

    /** O {@code type} estável da resposta de erro. */
    public String codigo() {
        return "servico-indisponivel";
    }
}
