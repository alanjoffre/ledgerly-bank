package io.github.alanjoffre.ledgerly.contas.aplicacao;

import io.github.alanjoffre.ledgerly.contas.aplicacao.porta.ContaRepository;
import io.github.alanjoffre.ledgerly.contas.aplicacao.porta.Razao;
import io.github.alanjoffre.ledgerly.contas.aplicacao.porta.RegistroDeEventos;
import io.github.alanjoffre.ledgerly.contas.dominio.Conta;
import io.github.alanjoffre.ledgerly.contas.dominio.ContaNaoEncontradaException;
import io.github.alanjoffre.ledgerly.contas.dominio.Cpf;
import io.github.alanjoffre.ledgerly.contas.dominio.EventoDeConta;
import io.github.alanjoffre.ledgerly.contas.dominio.EventoDeConta.ContaAberta;
import io.github.alanjoffre.ledgerly.contas.dominio.EventoDeConta.ContaBloqueada;
import io.github.alanjoffre.ledgerly.contas.dominio.EventoDeConta.ContaDesbloqueada;
import io.github.alanjoffre.ledgerly.contas.dominio.EventoDeConta.ContaEncerrada;
import io.github.alanjoffre.ledgerly.contas.dominio.NumeroConta;
import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso de conta.
 *
 * <p><b>Coordenação com o razão (ADR-0014):</b>
 *
 * <ul>
 *   <li><b>abrir, bloquear, desbloquear</b> — a mudança e o evento que avisa o razão são gravados na
 *       MESMA transação local (<i>Outbox</i>, ADR-0013). Não há chamada ao razão: com ele fora do
 *       ar, a operação funciona, e o evento chega quando ele voltar;</li>
 *   <li><b>encerrar</b> — continua síncrono: só o razão sabe se o saldo é zero, e a resposta decide
 *       se a conta pode ser encerrada. O evento {@code ContaEncerrada} também é gravado.</li>
 * </ul>
 */
@Service
public class ContaService {

    private final ContaRepository contas;
    private final ClienteService clientes;
    private final Razao razao;
    private final RegistroDeEventos eventos;
    private final Clock relogio;

    public ContaService(
            ContaRepository contas, ClienteService clientes, Razao razao, RegistroDeEventos eventos, Clock relogio) {
        this.contas = contas;
        this.clientes = clientes;
        this.razao = razao;
        this.eventos = eventos;
        this.relogio = relogio;
    }

    /** Conta com o saldo vindo do razão. */
    public record ContaComSaldo(Conta conta, Dinheiro saldo) {}

    @Transactional
    public Conta abrir(UUID clienteId) {
        clientes.consultar(clienteId);
        Conta conta = Conta.abrir(clienteId, NumeroConta.novo(contas.proximoNumero()), relogio.instant());
        contas.inserir(conta);
        eventos.registrar(new ContaAberta(conta.id(), clienteId, Dinheiro.REAL, conta.abertaEm()));
        return conta;
    }

    @Transactional
    public Conta bloquear(UUID id) {
        return alterar(id, Conta::bloquear, conta -> new ContaBloqueada(conta.id(), relogio.instant()));
    }

    @Transactional
    public Conta desbloquear(UUID id) {
        return alterar(id, Conta::desbloquear, conta -> new ContaDesbloqueada(conta.id(), relogio.instant()));
    }

    /**
     * Encerra a conta. O razão é chamado por último, ainda dentro da transação: se ele recusar
     * (saldo diferente de zero) ou estiver fora do ar, a transação é desfeita — nem a conta muda
     * aqui, nem o evento {@code ContaEncerrada} fica gravado.
     */
    @Transactional
    public Conta encerrar(UUID id) {
        Conta conta = alterar(
                id, c -> c.encerrar(relogio.instant()), c -> new ContaEncerrada(c.id(), c.encerradaEm()));
        razao.encerrarConta(id);
        return conta;
    }

    /**
     * Conta com o saldo do razão.
     *
     * <p>Se o razão ainda não conhece a conta, o saldo é zero — e isso é exato, não um palpite: o
     * razão só aceita movimento numa conta que ele conhece, e ele só passa a conhecê-la quando
     * consome o evento {@code ContaAberta}. Antes disso, nenhum depósito foi possível. É a janela da
     * consistência eventual (<i>eventual consistency</i>): normalmente o intervalo do publicador do
     * Outbox (100 ms) mais o tempo de o razão consumir o evento.
     */
    @Transactional(readOnly = true)
    public ContaComSaldo consultar(UUID id) {
        Conta conta = buscar(id);
        Dinheiro saldo = razao.consultarSaldo(id).orElseGet(() -> Dinheiro.zero(Dinheiro.REAL));
        return new ContaComSaldo(conta, saldo);
    }

    @Transactional(readOnly = true)
    public List<Conta> listarDoCliente(UUID clienteId) {
        clientes.consultar(clienteId);
        return contas.listarDoCliente(clienteId);
    }

    /** A conta e o nome do titular, sem o saldo. */
    public record ContaComTitular(Conta conta, String titularNome) {}

    /**
     * A conta e o titular, <b>sem consultar o razão</b>: é o que o Pix precisa para montar uma
     * transferência ("Pix de João da Silva"), e não pode depender de o razão estar no ar — se o
     * razão cair, a Saga do Pix espera por ele, mas o pedido tem de poder nascer.
     */
    @Transactional(readOnly = true)
    public ContaComTitular consultarTitular(UUID id) {
        Conta conta = buscar(id);
        return new ContaComTitular(conta, clientes.consultar(conta.clienteId()).nome());
    }

    /**
     * O CPF informado é o do titular da conta? Responde só sim ou não: o CPF completo do titular não
     * sai deste serviço. Usado pelo Pix para conferir uma chave do tipo CPF.
     */
    @Transactional(readOnly = true)
    public boolean titularTemCpf(UUID contaId, Cpf cpf) {
        Conta conta = buscar(contaId);
        return clientes.consultar(conta.clienteId()).cpf().equals(cpf);
    }

    private Conta alterar(UUID id, Consumer<Conta> transicao, Function<Conta, EventoDeConta> evento) {
        Conta conta = buscar(id);
        transicao.accept(conta); // regra da máquina de estados, no domínio
        contas.atualizar(conta); // trava otimista: falha se outra transação mudou a conta
        eventos.registrar(evento.apply(conta)); // só chega aqui quem venceu a trava
        return conta;
    }

    private Conta buscar(UUID id) {
        return contas.buscar(id)
                .orElseThrow(() -> new ContaNaoEncontradaException("Conta %s não encontrada".formatted(id)));
    }
}
