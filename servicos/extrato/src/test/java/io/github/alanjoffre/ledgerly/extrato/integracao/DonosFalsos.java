package io.github.alanjoffre.ledgerly.extrato.integracao;

import io.github.alanjoffre.ledgerly.extrato.aplicacao.ContasIndisponivelException;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.porta.DonoDaConta;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** O contas dos testes de integração: o teste diz de quem é cada conta, ou derruba o contas. */
public class DonosFalsos implements DonoDaConta {

    private final Map<UUID, UUID> donos = new ConcurrentHashMap<>();
    private final AtomicBoolean foraDoAr = new AtomicBoolean();

    public void definir(UUID contaId, UUID clienteId) {
        donos.put(contaId, clienteId);
    }

    public void derrubar(boolean fora) {
        foraDoAr.set(fora);
    }

    @Override
    public Optional<UUID> clienteDe(UUID contaId) {
        if (foraDoAr.get()) {
            throw new ContasIndisponivelException("contas fora do ar (teste)", null);
        }
        return Optional.ofNullable(donos.get(contaId));
    }
}
