package io.github.alanjoffre.ledgerly.pix.aplicacao.porta;

import io.github.alanjoffre.ledgerly.pix.dominio.TransferenciaPix;

/**
 * Porta de saída: a caixa de saída de eventos (Outbox, ADR-0013). Quem chama está dentro da
 * transação que mudou a transferência: o evento é gravado junto, ou não é gravado.
 */
public interface CaixaDeSaida {

    void pixConcluido(TransferenciaPix transferencia);

    void pixEstornado(TransferenciaPix transferencia);
}
