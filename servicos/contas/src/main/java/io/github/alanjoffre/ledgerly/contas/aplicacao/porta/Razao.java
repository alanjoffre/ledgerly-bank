package io.github.alanjoffre.ledgerly.contas.aplicacao.porta;

import io.github.alanjoffre.ledgerly.contas.dominio.SituacaoConta;
import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.util.UUID;

/**
 * Porta de saída: o serviço razão, dono do saldo. Todas as operações são idempotentes do lado do
 * razão — repetir uma chamada que falhou no meio é seguro (ADR-0011).
 */
public interface Razao {

    void abrirConta(UUID contaId);

    /**
     * @throws io.github.alanjoffre.ledgerly.contas.dominio.EncerramentoComSaldoException se pedir
     *     encerramento e o saldo não for zero
     */
    void alterarSituacao(UUID contaId, SituacaoConta situacao);

    Dinheiro consultarSaldo(UUID contaId);
}
