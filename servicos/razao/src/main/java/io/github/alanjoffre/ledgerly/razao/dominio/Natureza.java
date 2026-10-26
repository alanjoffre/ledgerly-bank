package io.github.alanjoffre.ledgerly.razao.dominio;

/**
 * Natureza contábil de uma conta: diz que lado da partida AUMENTA o saldo.
 *
 * <ul>
 *   <li>{@link #DEVEDORA} — contas de ativo (o que o banco tem), por exemplo o caixa.
 *       Débito aumenta, crédito diminui.</li>
 *   <li>{@link #CREDORA} — contas de passivo (o que o banco deve), por exemplo o dinheiro
 *       do cliente depositado no banco. Crédito aumenta, débito diminui.</li>
 * </ul>
 *
 * <p>É por isso que, no extrato do cliente, o depósito aparece como "crédito": para o banco,
 * é uma dívida que aumentou.
 */
public enum Natureza {
    DEVEDORA,
    CREDORA;

    /** O tipo de partida que aumenta o saldo de uma conta desta natureza. */
    public TipoPartida aumentaCom() {
        return this == DEVEDORA ? TipoPartida.DEBITO : TipoPartida.CREDITO;
    }
}
