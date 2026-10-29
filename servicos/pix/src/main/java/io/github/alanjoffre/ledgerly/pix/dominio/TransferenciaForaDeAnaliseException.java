package io.github.alanjoffre.ledgerly.pix.dominio;

import java.io.Serial;

/** A decisão de análise chegou para uma transferência que não está (ou não está mais) em análise. */
public class TransferenciaForaDeAnaliseException extends RegraDeNegocioException {

    @Serial
    private static final long serialVersionUID = 1L;

    public TransferenciaForaDeAnaliseException(String mensagem) {
        super(mensagem);
    }

    @Override
    public String codigo() {
        return "transferencia-fora-de-analise";
    }
}
