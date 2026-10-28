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
 *   <li>Cada conta interna, somadas as suas sub-contas, está com saldo não negativo? Uma sub-conta
 *       do Caixa pode ficar negativa; o Caixa inteiro, não (ADR-0019).</li>
 * </ol>
 */
public record RelatorioIntegridade(
        List<BalancoPorMoeda> balancos,
        List<UUID> contasComSaldoDivergente,
        List<UUID> lancamentosDesbalanceados,
        List<ContaInterna> contasInternas) {

    public RelatorioIntegridade {
        balancos = List.copyOf(balancos);
        contasComSaldoDivergente = List.copyOf(contasComSaldoDivergente);
        lancamentosDesbalanceados = List.copyOf(lancamentosDesbalanceados);
        contasInternas = List.copyOf(contasInternas);
    }

    public boolean integro() {
        return balancos.stream().allMatch(BalancoPorMoeda::fecha)
                && contasComSaldoDivergente.isEmpty()
                && lancamentosDesbalanceados.isEmpty()
                && contasInternas.stream().allMatch(ContaInterna::naoNegativa);
    }

    /**
     * Uma conta interna (Caixa ou Liquidação Pix) somada: o saldo é a soma das sub-contas.
     *
     * @param conta                 o id da conta-mãe (a sub-conta 0)
     * @param saldo                 a soma das sub-contas
     * @param subcontasNegativas    quantas sub-contas estão negativas agora (normal no Caixa)
     */
    public record ContaInterna(UUID conta, Dinheiro saldo, int subcontasNegativas) {

        public boolean naoNegativa() {
            return !saldo.ehNegativo();
        }
    }

    /** Totais de uma moeda. */
    public record BalancoPorMoeda(Dinheiro devedoras, Dinheiro credoras) {

        public boolean fecha() {
            return devedoras.equals(credoras);
        }
    }
}
