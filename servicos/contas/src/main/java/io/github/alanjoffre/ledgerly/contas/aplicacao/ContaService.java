package io.github.alanjoffre.ledgerly.contas.aplicacao;

import io.github.alanjoffre.ledgerly.contas.aplicacao.porta.ContaRepository;
import io.github.alanjoffre.ledgerly.contas.aplicacao.porta.Razao;
import io.github.alanjoffre.ledgerly.contas.dominio.Conta;
import io.github.alanjoffre.ledgerly.contas.dominio.ContaNaoEncontradaException;
import io.github.alanjoffre.ledgerly.contas.dominio.NumeroConta;
import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso de conta.
 *
 * <p><b>Coordenação com o razão (ADR-0011):</b> cada operação muda a conta aqui e avisa o razão
 * DENTRO da mesma transação local, antes do COMMIT:
 *
 * <ul>
 *   <li>o razão recusou ou está fora do ar → exceção → a transação local é desfeita: nada mudou;</li>
 *   <li>o razão aceitou e o COMMIT local falhou (raro) → o razão ficou adiantado. Como as chamadas
 *       ao razão são idempotentes, repetir a operação acerta os dois lados.</li>
 * </ul>
 *
 * Essa janela é aceita na Fase 1 e fechada na Fase 2 com o <i>Outbox Pattern</i>.
 */
@Service
public class ContaService {

    private final ContaRepository contas;
    private final ClienteService clientes;
    private final Razao razao;
    private final Clock relogio;

    public ContaService(ContaRepository contas, ClienteService clientes, Razao razao, Clock relogio) {
        this.contas = contas;
        this.clientes = clientes;
        this.razao = razao;
        this.relogio = relogio;
    }

    /** Conta com o saldo vindo do razão. */
    public record ContaComSaldo(Conta conta, Dinheiro saldo) {}

    @Transactional
    public Conta abrir(UUID clienteId) {
        clientes.consultar(clienteId);
        Conta conta = Conta.abrir(clienteId, NumeroConta.novo(contas.proximoNumero()), relogio.instant());
        contas.inserir(conta);
        razao.abrirConta(conta.id());
        return conta;
    }

    @Transactional
    public Conta bloquear(UUID id) {
        return alterar(id, Conta::bloquear);
    }

    @Transactional
    public Conta desbloquear(UUID id) {
        return alterar(id, Conta::desbloquear);
    }

    /** Encerra a conta; o razão recusa se o saldo não for zero, e aí nada muda aqui. */
    @Transactional
    public Conta encerrar(UUID id) {
        return alterar(id, conta -> conta.encerrar(relogio.instant()));
    }

    @Transactional(readOnly = true)
    public ContaComSaldo consultar(UUID id) {
        Conta conta = buscar(id);
        return new ContaComSaldo(conta, razao.consultarSaldo(id));
    }

    @Transactional(readOnly = true)
    public List<Conta> listarDoCliente(UUID clienteId) {
        clientes.consultar(clienteId);
        return contas.listarDoCliente(clienteId);
    }

    private Conta alterar(UUID id, Consumer<Conta> transicao) {
        Conta conta = buscar(id);
        transicao.accept(conta); // regra da máquina de estados, no domínio
        contas.atualizar(conta); // trava otimista: falha se outra transação mudou a conta
        razao.alterarSituacao(id, conta.situacao());
        return conta;
    }

    private Conta buscar(UUID id) {
        return contas.buscar(id)
                .orElseThrow(() -> new ContaNaoEncontradaException("Conta %s não encontrada".formatted(id)));
    }
}
