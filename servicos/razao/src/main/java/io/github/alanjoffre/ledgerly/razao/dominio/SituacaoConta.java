package io.github.alanjoffre.ledgerly.razao.dominio;

/**
 * Situação de uma conta no razão.
 *
 * <ul>
 *   <li>{@link #ATIVA} — movimenta normalmente;</li>
 *   <li>{@link #BLOQUEADA} — recebe crédito, mas não sai dinheiro (como num bloqueio judicial);</li>
 *   <li>{@link #ENCERRADA} — não movimenta mais; só encerra com saldo zero.</li>
 * </ul>
 */
public enum SituacaoConta {
    ATIVA,
    BLOQUEADA,
    ENCERRADA
}
