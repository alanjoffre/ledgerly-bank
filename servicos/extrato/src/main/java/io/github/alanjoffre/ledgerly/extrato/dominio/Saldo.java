package io.github.alanjoffre.ledgerly.extrato.dominio;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * O saldo de uma conta no modelo de leitura: o {@code saldoApos} da partida de <b>maior
 * sequência</b> que o extrato já recebeu.
 *
 * <p>Não é "o último evento que chegou": eventos de lançamentos diferentes podem chegar fora de
 * ordem. Um evento atrasado (sequência menor) não volta o saldo para trás — ver
 * {@link #ehMaisNovoQue(Saldo)}.
 *
 * @param contaId      a conta
 * @param valor        o saldo
 * @param sequencia    a partida de onde o saldo veio — diz "até onde" o extrato está atualizado
 * @param atualizadoEm quando essa partida foi escriturada no razão (milissegundos)
 */
public record Saldo(UUID contaId, Dinheiro valor, long sequencia, Instant atualizadoEm) {

    public Saldo {
        Objects.requireNonNull(contaId, "contaId é obrigatório");
        Objects.requireNonNull(valor, "valor é obrigatório");
        atualizadoEm = Objects.requireNonNull(atualizadoEm, "atualizadoEm é obrigatório").truncatedTo(ChronoUnit.MILLIS);
    }

    /** O saldo depois de uma partida desta conta. */
    public static Saldo depoisDe(UUID contaId, ParteDoRazao partida) {
        return new Saldo(contaId, partida.saldoApos(), partida.sequencia(), partida.registradoEm());
    }

    /** Este saldo veio de uma partida posterior à do outro? Só então ele substitui o outro. */
    public boolean ehMaisNovoQue(Saldo outro) {
        return outro == null || sequencia > outro.sequencia;
    }
}
