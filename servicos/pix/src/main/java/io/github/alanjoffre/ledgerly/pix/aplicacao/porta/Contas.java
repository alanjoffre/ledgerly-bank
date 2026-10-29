package io.github.alanjoffre.ledgerly.pix.aplicacao.porta;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída: o serviço de contas, para saber de quem é uma conta.
 *
 * @see io.github.alanjoffre.ledgerly.pix.aplicacao.ServicoIndisponivelException quando ele não responde
 */
public interface Contas {

    /** O resumo da conta: de qual cliente é, o nome do titular e se está ativa. */
    record ContaResumo(UUID contaId, UUID clienteId, String titularNome, boolean ativa) {}

    Optional<ContaResumo> buscar(UUID contaId);

    /** O CPF é o do titular da conta? O CPF completo não sai do serviço de contas: a resposta é sim ou não. */
    boolean titularidadeConfere(UUID contaId, String cpf);
}
