package io.github.alanjoffre.ledgerly.extrato.aplicacao;

import io.github.alanjoffre.ledgerly.extrato.dominio.Movimentacao;
import io.github.alanjoffre.ledgerly.extrato.dominio.Saldo;
import java.util.List;
import java.util.UUID;

/**
 * Uma página do extrato.
 *
 * @param contaId       a conta
 * @param saldo         o saldo atual no modelo de leitura ({@code null} se ainda não há)
 * @param itens         as linhas, da mais nova para a mais antiga
 * @param proximaPagina o valor de {@code antesDe} para pedir a página seguinte; {@code null} = acabou
 */
public record PaginaDoExtrato(UUID contaId, Saldo saldo, List<Movimentacao> itens, Long proximaPagina) {

    public PaginaDoExtrato {
        itens = List.copyOf(itens);
    }
}
