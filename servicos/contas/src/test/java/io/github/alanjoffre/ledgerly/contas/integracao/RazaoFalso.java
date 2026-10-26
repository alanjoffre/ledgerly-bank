package io.github.alanjoffre.ledgerly.contas.integracao;

import io.github.alanjoffre.ledgerly.contas.aplicacao.RazaoIndisponivelException;
import io.github.alanjoffre.ledgerly.contas.aplicacao.porta.Razao;
import io.github.alanjoffre.ledgerly.contas.dominio.EncerramentoComSaldoException;
import io.github.alanjoffre.ledgerly.contas.dominio.SituacaoConta;
import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dublê de teste (<i>fake</i>) do razão: guarda situação e saldo em memória e imita as regras que
 * importam para o serviço de contas. Pode ser "derrubado" para testar a indisponibilidade.
 * (O contrato real entre os dois serviços é testado com Pact na Fase 7.)
 */
class RazaoFalso implements Razao {

    private final Map<UUID, SituacaoConta> situacoes = new ConcurrentHashMap<>();
    private final Map<UUID, Dinheiro> saldos = new ConcurrentHashMap<>();
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

    SituacaoConta situacao(UUID conta) {
        return situacoes.get(conta);
    }

    boolean conhece(UUID conta) {
        return situacoes.containsKey(conta);
    }

    @Override
    public void abrirConta(UUID contaId) {
        exigirNoAr();
        situacoes.putIfAbsent(contaId, SituacaoConta.ATIVA);
        saldos.putIfAbsent(contaId, Dinheiro.zero(Dinheiro.REAL));
    }

    @Override
    public void alterarSituacao(UUID contaId, SituacaoConta situacao) {
        exigirNoAr();
        if (situacao == SituacaoConta.ENCERRADA && !saldos.get(contaId).ehZero()) {
            throw new EncerramentoComSaldoException("A conta tem saldo " + saldos.get(contaId));
        }
        situacoes.put(contaId, situacao);
    }

    @Override
    public Dinheiro consultarSaldo(UUID contaId) {
        exigirNoAr();
        return saldos.get(contaId);
    }

    private void exigirNoAr() {
        if (foraDoAr) {
            throw new RazaoIndisponivelException("razão derrubado pelo teste", null);
        }
    }
}
