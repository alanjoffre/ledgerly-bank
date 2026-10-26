package io.github.alanjoffre.ledgerly.razao.dominio;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.util.Objects;
import java.util.UUID;

/**
 * Uma linha de um lançamento já escriturado: débito ou crédito numa conta.
 *
 * @param contaId   a conta movimentada
 * @param tipo      débito ou crédito
 * @param valor     sempre positivo; o lado é dado pelo {@code tipo}
 * @param saldoApos o saldo da conta logo depois desta partida (vai pronto para o extrato)
 */
public record Partida(UUID contaId, TipoPartida tipo, Dinheiro valor, Dinheiro saldoApos) {

    public Partida {
        Objects.requireNonNull(contaId, "contaId é obrigatório");
        Objects.requireNonNull(tipo, "tipo é obrigatório");
        Objects.requireNonNull(valor, "valor é obrigatório");
        Objects.requireNonNull(saldoApos, "saldoApos é obrigatório");
        if (!valor.ehPositivo()) {
            throw new LancamentoInvalidoException("O valor de uma partida deve ser positivo: " + valor);
        }
    }

    /** A partida sem o saldo — o que foi pedido, para comparar pedidos repetidos. */
    public PedidoDePartida comoPedido() {
        return new PedidoDePartida(contaId, tipo, valor);
    }
}
