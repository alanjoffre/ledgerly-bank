package io.github.alanjoffre.ledgerly.razao.aplicacao;

import static io.github.alanjoffre.ledgerly.razao.dominio.PedidoDePartida.credito;
import static io.github.alanjoffre.ledgerly.razao.dominio.PedidoDePartida.debito;

import io.github.alanjoffre.ledgerly.razao.aplicacao.porta.CaixaDeSaida;
import io.github.alanjoffre.ledgerly.razao.aplicacao.porta.ContaRazaoRepository;
import io.github.alanjoffre.ledgerly.razao.aplicacao.porta.LancamentoRepository;
import io.github.alanjoffre.ledgerly.razao.dominio.ChaveIdempotencia;
import io.github.alanjoffre.ledgerly.razao.dominio.ChaveIdempotenciaReutilizadaException;
import io.github.alanjoffre.ledgerly.razao.dominio.ContaNaoEncontradaException;
import io.github.alanjoffre.ledgerly.razao.dominio.ContaRazao;
import io.github.alanjoffre.ledgerly.razao.dominio.ContasQuentes;
import io.github.alanjoffre.ledgerly.razao.dominio.EnvioPixInvalidoException;
import io.github.alanjoffre.ledgerly.razao.dominio.Escrituracao;
import io.github.alanjoffre.ledgerly.razao.dominio.Lancamento;
import io.github.alanjoffre.ledgerly.razao.dominio.OperacaoNaoPermitidaException;
import io.github.alanjoffre.ledgerly.razao.dominio.PedidoDePartida;
import io.github.alanjoffre.ledgerly.razao.dominio.SituacaoConta;
import io.github.alanjoffre.ledgerly.razao.dominio.TipoOperacao;
import io.github.alanjoffre.ledgerly.razao.dominio.TipoPartida;
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
 *   <li><b>Gravar</b> o lançamento, os saldos novos e o evento {@code LancamentoRegistrado} na
 *       caixa de saída (Outbox, ADR-0013).</li>
 * </ol>
 *
 * <p>Se qualquer passo falhar, a transação é desfeita inteira: nenhum centavo se move pela metade.
 *
 * <p><b>Contas quentes:</b> o Caixa e a Liquidação Pix são divididos em sub-contas, e cada pedido
 * trava uma só ({@link ContasQuentes}, ADR-0019).
 */
@Service
public class LancamentoService {

    private final ContaRazaoRepository contas;
    private final LancamentoRepository lancamentos;
    private final CaixaDeSaida caixaDeSaida;
    private final ContasQuentes quentes;
    private final Clock relogio;

    public LancamentoService(
            ContaRazaoRepository contas,
            LancamentoRepository lancamentos,
            CaixaDeSaida caixaDeSaida,
            ContasQuentes quentes,
            Clock relogio) {
        this.contas = contas;
        this.lancamentos = lancamentos;
        this.caixaDeSaida = caixaDeSaida;
        this.quentes = quentes;
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
                List.of(debito(quentes.caixaPara(comando.contaId()), comando.valor()),
                        credito(comando.contaId(), comando.valor())),
                null);
    }

    /** Saque em dinheiro: sai da conta do cliente (débito) e do Caixa (crédito). */
    @Transactional
    public ResultadoLancamento sacar(Comandos.Saque comando) {
        exigirContaDeCliente(comando.contaId());
        return registrar(
                comando.chave(),
                TipoOperacao.SAQUE,
                comando.descricao(),
                List.of(debito(comando.contaId(), comando.valor()),
                        credito(quentes.caixaPara(comando.contaId()), comando.valor())),
                null);
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
                List.of(debito(comando.origemId(), comando.valor()), credito(comando.destinoId(), comando.valor())),
                null);
    }

    /** Pix saindo: o dinheiro sai do pagador e fica na Liquidação Pix até o crédito no recebedor. */
    @Transactional
    public ResultadoLancamento enviarPix(Comandos.MovimentoPix comando) {
        exigirContaDeCliente(comando.contaId());
        return registrar(
                comando.chave(),
                TipoOperacao.PIX_ENVIO,
                comando.descricao(),
                List.of(debito(comando.contaId(), comando.valor()),
                        credito(quentes.liquidacaoPara(comando.contaId()), comando.valor())),
                null);
    }

    /** Pix chegando: o dinheiro sai da Liquidação Pix e entra na conta do recebedor. */
    @Transactional
    public ResultadoLancamento receberPix(Comandos.SaidaDaLiquidacao comando) {
        return daLiquidacaoPara(TipoOperacao.PIX_RECEBIMENTO, comando);
    }

    /**
     * Pix devolvido: o crédito no recebedor foi recusado, e o dinheiro sai da Liquidação Pix de
     * volta para o pagador. É a compensação da Saga do Pix (ADR-0015) — um lançamento novo, nunca
     * a alteração do envio (o razão é imutável).
     */
    @Transactional
    public ResultadoLancamento estornarPix(Comandos.SaidaDaLiquidacao comando) {
        return daLiquidacaoPara(TipoOperacao.PIX_ESTORNO, comando);
    }

    @Transactional(readOnly = true)
    public Lancamento consultar(UUID id) {
        return lancamentos
                .buscarPorId(id)
                .orElseThrow(() -> new ContaNaoEncontradaException("Lançamento %s não encontrado".formatted(id)));
    }

    /**
     * Recebimento ou estorno: o dinheiro sai da sub-conta da Liquidação Pix que o envio usou. O
     * envio é lido antes de travar qualquer conta — lançamento gravado não muda, então a leitura
     * sem trava é segura.
     */
    private ResultadoLancamento daLiquidacaoPara(TipoOperacao tipo, Comandos.SaidaDaLiquidacao comando) {
        exigirContaDeCliente(comando.contaId());
        Lancamento envio = lancamentos
                .buscarPorId(comando.lancamentoEnvioId())
                .filter(l -> l.tipo() == TipoOperacao.PIX_ENVIO)
                .orElseThrow(() -> new EnvioPixInvalidoException(
                        "O lançamento %s não é um envio de Pix".formatted(comando.lancamentoEnvioId())));
        if (!envio.valor().equals(comando.valor())) {
            throw new EnvioPixInvalidoException("O envio %s foi de %s, não de %s"
                    .formatted(envio.id(), envio.valor(), comando.valor()));
        }
        if (tipo == TipoOperacao.PIX_ESTORNO) {
            exigirDestinoDoEstorno(envio, comando.contaId());
        }
        UUID subconta = envio.subcontaInterna(ContaRazao.LIQUIDACAO_PIX).orElseThrow();
        return registrar(
                comando.chave(),
                tipo,
                comando.descricao(),
                List.of(debito(subconta, comando.valor()), credito(comando.contaId(), comando.valor())),
                envio.id());
    }

    /**
     * O estorno volta para a conta que pagou. A exceção é a devolução de um Pix em análise
     * (ADR-0030): a conta que pagou foi encerrada — o estado é final, ela nunca mais movimenta —, e
     * o analista decidiu outra conta do mesmo titular. Quem confere o titular é o Pix (o razão não
     * conhece pessoas); aqui fica a trava de que só a conta encerrada abre essa porta.
     */
    private void exigirDestinoDoEstorno(Lancamento envio, UUID destino) {
        UUID pagador = envio.contaDeClienteCom(TipoPartida.DEBITO).orElseThrow();
        if (pagador.equals(destino)) {
            return;
        }
        boolean pagadorEncerrou = contas.buscar(pagador)
                .filter(conta -> conta.situacao() == SituacaoConta.ENCERRADA)
                .isPresent();
        if (!pagadorEncerrou) {
            throw new EnvioPixInvalidoException(
                    "O estorno do envio %s só pode voltar para a conta que pagou (outra conta, só se ela foi encerrada)"
                            .formatted(envio.id()));
        }
    }

    private ResultadoLancamento registrar(
            ChaveIdempotencia chave,
            TipoOperacao tipo,
            String descricao,
            List<PedidoDePartida> pedidos,
            UUID envioPixId) {
        Map<UUID, ContaRazao> travadas = contas.travarParaAtualizacao(
                pedidos.stream().map(PedidoDePartida::contaId).toList());

        Optional<Lancamento> existente = lancamentos.buscarPorChave(chave);
        if (existente.isPresent()) {
            if (existente.get().correspondeA(tipo, pedidos, envioPixId)) {
                return new ResultadoLancamento(existente.get(), true);
            }
            throw new ChaveIdempotenciaReutilizadaException(
                    "A chave de idempotência '%s' já foi usada para outro pedido".formatted(chave));
        }
        // Depois da trava: o recebimento e o estorno do mesmo envio travam a mesma sub-conta da
        // Liquidação Pix, então o segundo a chegar enxerga o primeiro aqui.
        if (envioPixId != null && lancamentos.envioJaTeveSaida(envioPixId)) {
            throw new EnvioPixInvalidoException(
                    "O envio %s já teve a sua saída da Liquidação Pix (recebimento ou estorno)".formatted(envioPixId));
        }

        Lancamento lancamento = Escrituracao.escriturar(
                chave, tipo, descricao, pedidos, travadas, relogio.instant(), envioPixId);
        List<Long> sequencias = lancamentos.inserir(lancamento);
        contas.atualizar(travadas.values());
        caixaDeSaida.lancamentoRegistrado(lancamento, sequencias);
        return new ResultadoLancamento(lancamento, false);
    }

    /** As contas internas do banco (Caixa, Liquidação Pix) não podem ser movimentadas diretamente pela API. */
    private static void exigirContaDeCliente(UUID contaId) {
        if (ContaRazao.ehInterna(contaId)) {
            throw new OperacaoNaoPermitidaException(
                    "A conta %s é interna do banco e não pode ser movimentada diretamente".formatted(contaId));
        }
    }
}
