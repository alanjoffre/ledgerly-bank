package io.github.alanjoffre.ledgerly.contas.integracao;

import io.github.alanjoffre.ledgerly.contas.aplicacao.RazaoIndisponivelException;
import io.github.alanjoffre.ledgerly.contas.aplicacao.porta.Razao;
import io.github.alanjoffre.ledgerly.contas.dominio.EncerramentoComSaldoException;
import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dublê de teste (<i>fake</i>) do razão: guarda saldos em memória e imita as regras que importam
 * para o serviço de contas. Pode ser "derrubado" para testar a indisponibilidade.
 *
 * <p>Uma conta só existe aqui depois de {@link #definirSaldo}: é o papel do evento {@code ContaAberta}
 * que o razão real consome. Sem isso, o falso responde como o razão que ainda não recebeu o evento.
 * (O contrato real entre os dois serviços é testado com Pact na Fase 7.)
 */
class RazaoFalso implements Razao {

    private final Map<UUID, Dinheiro> saldos = new ConcurrentHashMap<>();
    private final Set<UUID> encerradas = ConcurrentHashMap.newKeySet();
    private volatile boolean foraDoAr;

    void derrubar() {
        foraDoAr = true;
    }

    void religar() {
        foraDoAr = false;
    }

    void definirSaldo(UUID conta, String valor) {
        saldos.put(conta, Dinheiro.reais(valor));
    }

    boolean encerrada(UUID conta) {
        return encerradas.contains(conta);
    }

    @Override
    public void encerrarConta(UUID contaId) {
        exigirNoAr();
        Dinheiro saldo = saldos.get(contaId);
        if (saldo != null && !saldo.ehZero()) {
            throw new EncerramentoComSaldoException("A conta tem saldo " + saldo);
        }
        encerradas.add(contaId);
    }

    @Override
    public Optional<Dinheiro> consultarSaldo(UUID contaId) {
        exigirNoAr();
        return Optional.ofNullable(saldos.get(contaId));
    }

    private void exigirNoAr() {
        if (foraDoAr) {
            throw new RazaoIndisponivelException("razão derrubado pelo teste", null);
        }
    }
}
