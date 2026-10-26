package io.github.alanjoffre.ledgerly.razao.dominio;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.util.Objects;
import java.util.UUID;

/**
 * Uma partida pedida, ainda não aplicada a nenhuma conta (por isso sem saldo).
 *
 * @param contaId a conta a movimentar
 * @param tipo    débito ou crédito
 * @param valor   sempre positivo
 */
public record PedidoDePartida(UUID contaId, TipoPartida tipo, Dinheiro valor) {

    public PedidoDePartida {
        Objects.requireNonNull(contaId, "contaId é obrigatório");
        Objects.requireNonNull(tipo, "tipo é obrigatório");
        Objects.requireNonNull(valor, "valor é obrigatório");
        if (!valor.ehPositivo()) {
            throw new LancamentoInvalidoException("O valor de uma partida deve ser positivo: " + valor);
        }
    }

    public static PedidoDePartida debito(UUID contaId, Dinheiro valor) {
        return new PedidoDePartida(contaId, TipoPartida.DEBITO, valor);
    }

    public static PedidoDePartida credito(UUID contaId, Dinheiro valor) {
        return new PedidoDePartida(contaId, TipoPartida.CREDITO, valor);
    }
}
