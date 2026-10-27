package io.github.alanjoffre.ledgerly.extrato.adaptadores.entrada.kafka;

import java.io.Serial;

/**
 * A mensagem não segue o contrato ({@code docs/arquitetura/eventos.md}): não é JSON, falta um
 * campo, um valor tem formato errado.
 *
 * <p>Tentar de novo não adianta — a mensagem vai continuar errada. Por isso este erro manda a
 * mensagem direto para o tópico de mensagens mortas (DLT), sem novas tentativas, e o consumidor
 * segue para a próxima.
 */
public class EventoInvalidoException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public EventoInvalidoException(String mensagem) {
        super(mensagem);
    }

    public EventoInvalidoException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
