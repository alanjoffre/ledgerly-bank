package io.github.alanjoffre.ledgerly.contas.aplicacao;

import java.io.Serial;

/**
 * O serviço razão não respondeu (fora do ar, lento demais, erro interno). Não é regra de negócio:
 * é falha de infraestrutura, e a API responde 503 para o cliente tentar de novo mais tarde.
 */
public final class RazaoIndisponivelException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public RazaoIndisponivelException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
