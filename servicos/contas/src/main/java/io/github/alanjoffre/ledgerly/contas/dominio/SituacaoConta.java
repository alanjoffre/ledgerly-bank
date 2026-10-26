package io.github.alanjoffre.ledgerly.contas.dominio;

/** Situação da conta. As transições permitidas estão em {@link Conta}. */
public enum SituacaoConta {
    ATIVA,
    BLOQUEADA,
    ENCERRADA
}
