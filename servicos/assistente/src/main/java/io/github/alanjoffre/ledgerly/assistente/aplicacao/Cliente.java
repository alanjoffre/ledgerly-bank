package io.github.alanjoffre.ledgerly.assistente.aplicacao;

import java.util.Objects;
import java.util.UUID;

/**
 * Quem pergunta: o cliente do token, e o próprio token, que segue para o contas e o extrato. As
 * ferramentas leem com o token da pessoa, não com um token de serviço — então leem só o que ela
 * leria na tela, e o BOLA (ADR-0026) continua sendo conferido lá.
 */
public record Cliente(UUID clienteId, String token) {

    public Cliente {
        Objects.requireNonNull(clienteId, "clienteId");
        Objects.requireNonNull(token, "token");
    }

    /** O token não vai para o log. */
    @Override
    public String toString() {
        return "Cliente[" + clienteId + "]";
    }
}
