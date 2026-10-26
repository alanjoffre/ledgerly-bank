package io.github.alanjoffre.ledgerly.razao.aplicacao;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.util.List;
import java.util.UUID;

/**
 * Resultado da conferência do razão — uma conciliação interna. Responde a três perguntas:
 *
 * <ol>
 *   <li>O balanço fecha? Em cada moeda, a soma das contas devedoras é igual à das credoras.</li>
 *   <li>O saldo gravado de cada conta é igual à soma das suas partidas?</li>
 *   <li>Todo lançamento tem débitos = créditos?</li>
 * </ol>
 */
public record RelatorioIntegridade(
        List<BalancoPorMoeda> balancos,
        List<UUID> contasComSaldoDivergente,
        List<UUID> lancamentosDesbalanceados) {

    public RelatorioIntegridade {
        balancos = List.copyOf(balancos);
        contasComSaldoDivergente = List.copyOf(contasComSaldoDivergente);
        lancamentosDesbalanceados = List.copyOf(lancamentosDesbalanceados);
    }

    public boolean integro() {
        return balancos.stream().allMatch(BalancoPorMoeda::fecha)
                && contasComSaldoDivergente.isEmpty()
                && lancamentosDesbalanceados.isEmpty();
    }

    /** Totais de uma moeda. */
    public record BalancoPorMoeda(Dinheiro devedoras, Dinheiro credoras) {

        public boolean fecha() {
            return devedoras.equals(credoras);
        }
    }
}
