package io.github.alanjoffre.ledgerly.assistente.aplicacao;

import io.github.alanjoffre.ledgerly.assistente.aplicacao.porta.Bancario;
import io.github.alanjoffre.ledgerly.assistente.dominio.Conta;
import io.github.alanjoffre.ledgerly.assistente.dominio.Lancamento;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** O contas e o extrato dos testes de unidade: guarda as contas, os saldos e quem foi lido. */
public class BancarioFalso implements Bancario {

    public final List<Conta> contas = new ArrayList<>();
    public final Map<UUID, BigDecimal> saldos = new HashMap<>();
    public final Map<UUID, List<Lancamento>> lancamentos = new HashMap<>();
    public final List<UUID> lidas = new ArrayList<>();
    public boolean foraDoAr;

    public Conta conta(String numero, String saldo) {
        Conta conta = new Conta(UUID.randomUUID(), "0001", numero);
        contas.add(conta);
        saldos.put(conta.id(), new BigDecimal(saldo));
        lancamentos.put(conta.id(), new ArrayList<>());
        return conta;
    }

    @Override
    public List<Conta> contas(Cliente cliente) {
        cair();
        return contas;
    }

    @Override
    public BigDecimal saldo(Cliente cliente, UUID contaId) {
        cair();
        lidas.add(contaId);
        return saldos.get(contaId);
    }

    @Override
    public List<Lancamento> lancamentos(Cliente cliente, UUID contaId, int limite) {
        cair();
        lidas.add(contaId);
        return lancamentos.get(contaId).stream().limit(limite).toList();
    }

    private void cair() {
        if (foraDoAr) {
            throw new BancarioIndisponivelException("fora do ar (teste)", null);
        }
    }
}
