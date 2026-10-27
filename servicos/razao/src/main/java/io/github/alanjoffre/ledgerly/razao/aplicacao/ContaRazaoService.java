package io.github.alanjoffre.ledgerly.razao.aplicacao;

import io.github.alanjoffre.ledgerly.razao.aplicacao.porta.ContaRazaoRepository;
import io.github.alanjoffre.ledgerly.razao.aplicacao.porta.IntegridadeRepository;
import io.github.alanjoffre.ledgerly.razao.aplicacao.porta.LancamentoRepository;
import io.github.alanjoffre.ledgerly.razao.dominio.ContaJaExisteException;
import io.github.alanjoffre.ledgerly.razao.dominio.ContaNaoEncontradaException;
import io.github.alanjoffre.ledgerly.razao.dominio.ContaRazao;
import io.github.alanjoffre.ledgerly.razao.dominio.OperacaoNaoPermitidaException;
import io.github.alanjoffre.ledgerly.razao.dominio.SituacaoConta;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Casos de uso das contas do razão: abrir, mudar situação, consultar saldo e extrato. */
@Service
public class ContaRazaoService {

    public static final int LIMITE_MAXIMO_EXTRATO = 500;

    private final ContaRazaoRepository contas;
    private final LancamentoRepository lancamentos;
    private final IntegridadeRepository integridade;

    public ContaRazaoService(
            ContaRazaoRepository contas, LancamentoRepository lancamentos, IntegridadeRepository integridade) {
        this.contas = contas;
        this.lancamentos = lancamentos;
        this.integridade = integridade;
    }

    /** @param conta a conta (nova ou a que já existia); @param criada se foi criada agora */
    public record ResultadoAbertura(ContaRazao conta, boolean criada) {}

    /**
     * Abre a conta de um cliente no razão. <b>Idempotente</b>: pedir de novo com o mesmo id e a
     * mesma moeda devolve a conta existente; com moeda diferente, é erro.
     *
     * <p>Quem pede é o serviço de contas, pelo evento {@code ContaAberta} (ADR-0014).
     */
    @Transactional
    public ResultadoAbertura abrir(UUID id, Currency moeda) {
        if (ContaRazao.ehInterna(id)) {
            throw new OperacaoNaoPermitidaException("O id %s é de uma conta interna do banco".formatted(id));
        }
        ContaRazao nova = ContaRazao.abrirContaDeCliente(id, moeda);
        if (contas.inserirSeAusente(nova)) {
            return new ResultadoAbertura(nova, true);
        }
        ContaRazao existente = consultar(id);
        if (!existente.moeda().equals(moeda) || !existente.natureza().equals(nova.natureza())) {
            throw new ContaJaExisteException("Já existe a conta %s, com outra moeda ou natureza".formatted(id));
        }
        return new ResultadoAbertura(existente, false);
    }

    /** Bloqueia, desbloqueia ou encerra a conta. A conta é travada para não correr com um débito. */
    @Transactional
    public ContaRazao alterarSituacao(UUID id, SituacaoConta situacao) {
        if (ContaRazao.ehInterna(id)) {
            throw new OperacaoNaoPermitidaException("A situação de uma conta interna do banco não pode ser alterada");
        }
        ContaRazao conta = contas.travarParaAtualizacao(Set.of(id)).get(id);
        if (conta == null) {
            throw naoEncontrada(id);
        }
        conta.alterarSituacao(situacao);
        contas.atualizar(List.of(conta));
        return conta;
    }

    @Transactional(readOnly = true)
    public ContaRazao consultar(UUID id) {
        return contas.buscar(id).orElseThrow(() -> naoEncontrada(id));
    }

    @Transactional(readOnly = true)
    public List<ItemExtrato> extrato(UUID id, int limite) {
        if (limite < 1 || limite > LIMITE_MAXIMO_EXTRATO) {
            throw new IllegalArgumentException(
                    "O limite do extrato deve estar entre 1 e %d".formatted(LIMITE_MAXIMO_EXTRATO));
        }
        consultar(id);
        return lancamentos.extrato(id, limite);
    }

    @Transactional(readOnly = true)
    public RelatorioIntegridade verificarIntegridade() {
        return integridade.verificar();
    }

    private static ContaNaoEncontradaException naoEncontrada(UUID id) {
        return new ContaNaoEncontradaException("Conta %s não encontrada no razão".formatted(id));
    }
}
