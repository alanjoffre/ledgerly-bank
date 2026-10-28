package io.github.alanjoffre.ledgerly.razao.dominio;

/** O que originou um lançamento. */
public enum TipoOperacao {
    DEPOSITO,
    SAQUE,
    TRANSFERENCIA,
    /** Pix saindo: cliente pagador → Liquidação Pix. */
    PIX_ENVIO,
    /** Pix chegando: Liquidação Pix → cliente recebedor. */
    PIX_RECEBIMENTO,
    /** Pix devolvido: Liquidação Pix → cliente pagador, quando o crédito no recebedor foi recusado. */
    PIX_ESTORNO;

    /**
     * Recebimento e estorno tiram dinheiro da Liquidação Pix, e cada um aponta para o envio que
     * colocou esse dinheiro lá (ADR-0019). Um envio tem no máximo uma saída.
     */
    public boolean ehSaidaDaLiquidacao() {
        return this == PIX_RECEBIMENTO || this == PIX_ESTORNO;
    }
}
