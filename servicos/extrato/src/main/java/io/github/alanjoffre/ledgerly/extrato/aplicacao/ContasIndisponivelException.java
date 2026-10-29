package io.github.alanjoffre.ledgerly.extrato.aplicacao;

/** O contas não respondeu quem é o dono da conta: o extrato do cliente fica indisponível (503). */
public class ContasIndisponivelException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ContasIndisponivelException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
