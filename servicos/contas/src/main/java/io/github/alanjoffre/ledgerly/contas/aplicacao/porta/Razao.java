package io.github.alanjoffre.ledgerly.contas.aplicacao.porta;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída: o serviço razão, dono do saldo — só para o que precisa de uma RESPOSTA dele.
 *
 * <p>Abrir, bloquear e desbloquear não perguntam nada ao razão: só avisam, por evento
 * ({@link RegistroDeEventos}). Ficaram aqui as duas operações que dependem do saldo, que só o razão
 * conhece (ADR-0014).
 */
public interface Razao {

    /**
     * Encerra a conta no razão. Idempotente: repetir não muda nada.
     *
     * @throws io.github.alanjoffre.ledgerly.contas.dominio.EncerramentoComSaldoException se o saldo
     *     não for zero
     */
    void encerrarConta(UUID contaId);

    /**
     * O saldo da conta, ou vazio se o razão ainda não conhece a conta (o evento {@code ContaAberta}
     * ainda não chegou lá).
     */
    Optional<Dinheiro> consultarSaldo(UUID contaId);
}
