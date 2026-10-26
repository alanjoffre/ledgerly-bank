package io.github.alanjoffre.ledgerly.contas.aplicacao;

import java.io.Serial;

/**
 * Duas alterações simultâneas na mesma conta: a segunda a gravar perde (trava otimista) e recebe
 * este erro. A API responde 409 Conflict, e quem chamou pode reler a conta e decidir de novo.
 */
public final class ConflitoDeAtualizacaoException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ConflitoDeAtualizacaoException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
