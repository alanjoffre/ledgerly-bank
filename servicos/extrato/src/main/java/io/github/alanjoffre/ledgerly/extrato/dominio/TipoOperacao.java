package io.github.alanjoffre.ledgerly.extrato.dominio;

/** O que originou o lançamento no razão (o campo {@code tipo} do {@code LancamentoRegistrado}). */
public enum TipoOperacao {
    DEPOSITO,
    SAQUE,
    TRANSFERENCIA,
    PIX_ENVIO,
    PIX_RECEBIMENTO,
    PIX_ESTORNO
}
