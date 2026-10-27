package io.github.alanjoffre.ledgerly.pix.dominio;

/**
 * As situações de uma transferência Pix — os estados da Saga (ADR-0015).
 *
 * <pre>
 *   CRIADA ──débito ok──▶ DEBITADA ──crédito ok──▶ CONCLUIDA
 *     │                      │
 *     └─débito recusado──▶ REJEITADA (nenhum dinheiro saiu)
 *                            │
 *                            └─crédito recusado──▶ ESTORNANDO ──estorno ok──▶ ESTORNADA
 *                                                     │
 *                                                     └─estorno recusado──▶ EM_ANALISE (pessoas)
 * </pre>
 *
 * Sem resposta (razão fora do ar, tempo esgotado) a situação NÃO muda: o mesmo passo é repetido
 * mais tarde, com a mesma chave de idempotência.
 */
public enum SituacaoTransferencia {
    CRIADA(false),
    DEBITADA(false),
    CONCLUIDA(true),
    REJEITADA(true),
    ESTORNANDO(false),
    ESTORNADA(true),
    EM_ANALISE(true);

    private final boolean fim;

    SituacaoTransferencia(boolean fim) {
        this.fim = fim;
    }

    /** Situação final: a Saga não tem mais passo automático a dar. */
    public boolean ehFinal() {
        return fim;
    }
}
