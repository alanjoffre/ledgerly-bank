package io.github.alanjoffre.ledgerly.razao.aplicacao;

import static io.github.alanjoffre.ledgerly.razao.dominio.PedidoDePartida.credito;
import static io.github.alanjoffre.ledgerly.razao.dominio.PedidoDePartida.debito;

import io.github.alanjoffre.ledgerly.razao.aplicacao.porta.ContaRazaoRepository;
import io.github.alanjoffre.ledgerly.razao.aplicacao.porta.LancamentoRepository;
import io.github.alanjoffre.ledgerly.razao.dominio.ChaveIdempotencia;
import io.github.alanjoffre.ledgerly.razao.dominio.ChaveIdempotenciaReutilizadaException;
import io.github.alanjoffre.ledgerly.razao.dominio.ContaNaoEncontradaException;
import io.github.alanjoffre.ledgerly.razao.dominio.ContaRazao;
import io.github.alanjoffre.ledgerly.razao.dominio.Escrituracao;
import io.github.alanjoffre.ledgerly.razao.dominio.Lancamento;
import io.github.alanjoffre.ledgerly.razao.dominio.OperacaoNaoPermitidaException;
import io.github.alanjoffre.ledgerly.razao.dominio.PedidoDePartida;
import io.github.alanjoffre.ledgerly.razao.dominio.TipoOperacao;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso que movem dinheiro: depósito, saque e transferência.
 *
 * <p>Todos seguem o mesmo roteiro, dentro de UMA transação de banco:
 *
 * <ol>
 *   <li><b>Travar</b> as contas envolvidas, em ordem de id (evita deadlock);</li>
 *   <li><b>Conferir a chave de idempotência</b> — depois de travar, para que dois pedidos iguais
 *       simultâneos fiquem em fila e o segundo enxergue o lançamento do primeiro;</li>
 *   <li><b>Escriturar</b> no domínio (regras de saldo, situação e partidas dobradas);</li>
 *   <li><b>Gravar</b> o lançamento e os saldos novos.</li>
 * </ol>
 *
 * <p>Se qualquer passo falhar, a transação é desfeita inteira: nenhum centavo se move pela metade.
 */
@Service
public class LancamentoService {

    private final ContaRazaoRepository contas;
    private final LancamentoRepository lancamentos;
    private final Clock relogio;

    public LancamentoService(ContaRazaoRepository contas, LancamentoRepository lancamentos, Clock relogio) {
        this.contas = contas;
        this.lancamentos = lancamentos;
        this.relogio = relogio;
    }

    /** Depósito em dinheiro: entra no Caixa do banco (débito) e na conta do cliente (crédito). */
    @Transactional
    public ResultadoLancamento depositar(Comandos.Deposito comando) {
        exigirContaDeCliente(comando.contaId());
        return registrar(
                comando.chave(),
                TipoOperacao.DEPOSITO,
                comando.descricao(),
                List.of(debito(ContaRazao.CAIXA, comando.valor()), credito(comando.contaId(), comando.valor())));
    }

    /** Saque em dinheiro: sai da conta do cliente (débito) e do Caixa (crédito). */
    @Transactional
    public ResultadoLancamento sacar(Comandos.Saque comando) {
        exigirContaDeCliente(comando.contaId());
        return registrar(
                comando.chave(),
                TipoOperacao.SAQUE,
                comando.descricao(),
                List.of(debito(comando.contaId(), comando.valor()), credito(ContaRazao.CAIXA, comando.valor())));
    }

    /** Transferência entre duas contas de cliente: débito na origem, crédito no destino. */
    @Transactional
    public ResultadoLancamento transferir(Comandos.Transferencia comando) {
        exigirContaDeCliente(comando.origemId());
        exigirContaDeCliente(comando.destinoId());
        if (comando.origemId().equals(comando.destinoId())) {
            throw new OperacaoNaoPermitidaException("Origem e destino da transferência são a mesma conta");
        }
        return registrar(
                comando.chave(),
                TipoOperacao.TRANSFERENCIA,
                comando.descricao(),
                List.of(debito(comando.origemId(), comando.valor()), credito(comando.destinoId(), comando.valor())));
    }

    @Transactional(readOnly = true)
    public Lancamento consultar(UUID id) {
        return lancamentos
                .buscarPorId(id)
                .orElseThrow(() -> new ContaNaoEncontradaException("Lançamento %s não encontrado".formatted(id)));
    }

    private ResultadoLancamento registrar(
            ChaveIdempotencia chave, TipoOperacao tipo, String descricao, List<PedidoDePartida> pedidos) {
        Map<UUID, ContaRazao> travadas = contas.travarParaAtualizacao(
                pedidos.stream().map(PedidoDePartida::contaId).toList());

        Optional<Lancamento> existente = lancamentos.buscarPorChave(chave);
        if (existente.isPresent()) {
            if (existente.get().correspondeA(tipo, pedidos)) {
                return new ResultadoLancamento(existente.get(), true);
            }
            throw new ChaveIdempotenciaReutilizadaException(
                    "A chave de idempotência '%s' já foi usada para outro pedido".formatted(chave));
        }

        Lancamento lancamento = Escrituracao.escriturar(chave, tipo, descricao, pedidos, travadas, relogio.instant());
        lancamentos.inserir(lancamento);
        contas.atualizar(travadas.values());
        return new ResultadoLancamento(lancamento, false);
    }

    /** As contas internas do banco (como o Caixa) não podem ser movimentadas diretamente pela API. */
    private static void exigirContaDeCliente(UUID contaId) {
        if (ContaRazao.CAIXA.equals(contaId)) {
            throw new OperacaoNaoPermitidaException("A conta interna Caixa não pode ser movimentada diretamente");
        }
    }
}
