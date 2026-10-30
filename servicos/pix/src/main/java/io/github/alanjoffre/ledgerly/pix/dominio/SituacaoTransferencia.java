package io.github.alanjoffre.ledgerly.pix.dominio;

/**
 * As situações de uma transferência Pix — os estados da Saga (ADR-0015).
 *
 * <pre>
 *   Antes do dinheiro (Fase 5, ADR-0033):
 *   CRIADA ──antifraude aprova──▶ APROVADA
 *     ├────antifraude segura──▶ RETIDA ──a analista libera──▶ APROVADA
 *     │                           └─────a analista recusa──▶ REJEITADA
 *     └────antifraude recusa──▶ REJEITADA (nenhum dinheiro saiu)
 *
 *   O dinheiro (ADR-0015):
 *   APROVADA ──débito ok──▶ DEBITADA ──crédito ok──▶ CONCLUIDA
 *     │                      │
 *     └─débito recusado──▶ REJEITADA (nenhum dinheiro saiu)
 *                            │
 *                            └─crédito recusado──▶ ESTORNANDO ──estorno ok──▶ ESTORNADA
 *                                                     │
 *                                                     └─estorno recusado──▶ EM_ANALISE (pessoas)
 * </pre>
 *
 * Sem resposta do razão (fora do ar, tempo esgotado) a situação NÃO muda: o mesmo passo é repetido
 * mais tarde, com a mesma chave de idempotência. Sem resposta do antifraude, vale a falha segura
 * ({@link TransferenciaPix#LIMITE_SEM_ANTIFRAUDE}).
 */
public enum SituacaoTransferencia {
    CRIADA(false),
    /** O antifraude deixou (ou, fora do ar, a regra de valor deixou): o próximo passo é o débito. */
    APROVADA(false),
    /** Parada antes do débito, esperando a analista liberar ou recusar. */
    RETIDA(true),
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

    /**
     * A resposta ao cliente já é a definitiva? O Pix retido ainda pode sair (a analista libera), então
     * não é: a API responde {@code 202}, como a um Pix em andamento.
     */
    public boolean respostaDefinitiva() {
        return fim && this != RETIDA;
    }
}
