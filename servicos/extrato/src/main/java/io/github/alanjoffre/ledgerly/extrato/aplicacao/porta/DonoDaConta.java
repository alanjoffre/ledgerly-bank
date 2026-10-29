package io.github.alanjoffre.ledgerly.extrato.aplicacao.porta;

import java.util.Optional;
import java.util.UUID;

/**
 * De quem é a conta: o id do cliente no serviço de contas. Vazio se a conta não existe.
 *
 * @throws io.github.alanjoffre.ledgerly.extrato.aplicacao.ContasIndisponivelException o contas não respondeu
 */
public interface DonoDaConta {

    Optional<UUID> clienteDe(UUID contaId);
}
