package io.github.alanjoffre.ledgerly.extrato.dominio;

/** O que esta linha do extrato representa dentro de um Pix, do ponto de vista do dono da conta. */
public enum PapelNoPix {
    /** O dono da conta pagou: o dinheiro saiu. */
    ENVIADO,
    /** O dono da conta recebeu: o dinheiro entrou. */
    RECEBIDO,
    /** O Pix que o dono da conta mandou não chegou, e o dinheiro voltou. */
    ESTORNO
}
