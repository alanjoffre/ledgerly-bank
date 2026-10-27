package io.github.alanjoffre.ledgerly.contas.aplicacao.porta;

import io.github.alanjoffre.ledgerly.contas.dominio.EventoDeConta;

/**
 * Porta de saída: guarda um evento para ser publicado depois (ADR-0013, <i>Outbox Pattern</i>).
 *
 * <p>Não publica nada na hora. O evento entra na <b>mesma transação</b> da mudança que o originou:
 * se a transação for desfeita, o evento também some; se for confirmada, um publicador separado o
 * entrega ao Kafka — mesmo que o Kafka esteja fora do ar agora.
 */
public interface RegistroDeEventos {

    void registrar(EventoDeConta evento);
}
