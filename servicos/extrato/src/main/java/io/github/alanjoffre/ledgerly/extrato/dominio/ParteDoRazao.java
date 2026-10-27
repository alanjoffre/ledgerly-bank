package io.github.alanjoffre.ledgerly.extrato.dominio;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * O pedaço de uma linha do extrato que vem do razão (evento {@code LancamentoRegistrado}): quanto,
 * em que sentido, e o saldo depois. É o pedaço obrigatório — sem ele a linha não aparece.
 *
 * @param sequencia    número da partida no razão; cresce sempre e ordena o extrato
 * @param operacao     o que originou o lançamento
 * @param tipo         débito (saiu) ou crédito (entrou)
 * @param valor        quanto se moveu
 * @param saldoApos    saldo da conta logo depois desta partida, calculado pelo razão
 * @param mensagem     o texto livre do lançamento ("Aluguel"), se houver
 * @param registradoEm quando o razão escriturou, em milissegundos (a precisão do MongoDB)
 */
public record ParteDoRazao(
        long sequencia,
        TipoOperacao operacao,
        TipoPartida tipo,
        Dinheiro valor,
        Dinheiro saldoApos,
        String mensagem,
        Instant registradoEm) {

    public ParteDoRazao {
        if (sequencia <= 0) {
            throw new IllegalArgumentException("a sequência da partida deve ser positiva");
        }
        Objects.requireNonNull(operacao, "operação é obrigatória");
        Objects.requireNonNull(tipo, "tipo é obrigatório");
        Objects.requireNonNull(valor, "valor é obrigatório");
        Objects.requireNonNull(saldoApos, "saldo após é obrigatório");
        // O razão manda microssegundos; o MongoDB guarda datas em milissegundos. Cortando aqui, o
        // que entra é exatamente o que sai do banco — a mesma armadilha do razão (nanos x micros).
        registradoEm = Objects.requireNonNull(registradoEm, "registradoEm é obrigatório").truncatedTo(ChronoUnit.MILLIS);
    }
}
