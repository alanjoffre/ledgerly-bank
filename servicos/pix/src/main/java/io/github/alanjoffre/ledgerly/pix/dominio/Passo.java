package io.github.alanjoffre.ledgerly.pix.dominio;

/** Os passos da Saga do Pix, cada um uma chamada ao razão. */
public enum Passo {
    /** Pagador → Liquidação Pix. */
    DEBITAR("envio"),
    /** Liquidação Pix → recebedor. */
    CREDITAR("recebimento"),
    /** Liquidação Pix → pagador: a compensação do débito. */
    ESTORNAR("estorno");

    private final String sufixo;

    Passo(String sufixo) {
        this.sufixo = sufixo;
    }

    /**
     * A chave de idempotência do passo no razão: {@code pix:<id>:envio}. Fixa por transferência e
     * por passo — repetir o passo mil vezes move o dinheiro uma vez só.
     */
    public String chaveDeIdempotencia(java.util.UUID transferenciaId) {
        return "pix:" + transferenciaId + ":" + sufixo;
    }
}
