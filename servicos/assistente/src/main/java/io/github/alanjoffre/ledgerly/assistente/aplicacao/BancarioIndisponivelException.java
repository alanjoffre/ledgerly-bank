package io.github.alanjoffre.ledgerly.assistente.aplicacao;

/** O contas ou o extrato não respondeu (fora do ar, lento, ou respondeu erro). */
public class BancarioIndisponivelException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BancarioIndisponivelException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
