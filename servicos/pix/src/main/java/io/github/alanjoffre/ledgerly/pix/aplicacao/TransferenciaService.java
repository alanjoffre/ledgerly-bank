package io.github.alanjoffre.ledgerly.pix.aplicacao;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.ChaveRepository;
import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.Contas;
import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.TransferenciaRepository;
import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.TravaDePedido;
import io.github.alanjoffre.ledgerly.pix.dominio.BrCode;
import io.github.alanjoffre.ledgerly.pix.dominio.ChaveIdempotenciaReutilizadaException;
import io.github.alanjoffre.ledgerly.pix.dominio.ChaveNaoEncontradaException;
import io.github.alanjoffre.ledgerly.pix.dominio.ChavePix;
import io.github.alanjoffre.ledgerly.pix.dominio.ChaveRegistrada;
import io.github.alanjoffre.ledgerly.pix.dominio.ContaDeOutroTitularException;
import io.github.alanjoffre.ledgerly.pix.dominio.ContaInativaException;
import io.github.alanjoffre.ledgerly.pix.dominio.ContaNaoEncontradaException;
import io.github.alanjoffre.ledgerly.pix.dominio.DecisaoDeAnalise;
import io.github.alanjoffre.ledgerly.pix.dominio.Participante;
import io.github.alanjoffre.ledgerly.pix.dominio.SituacaoTransferencia;
import io.github.alanjoffre.ledgerly.pix.dominio.TransferenciaInvalidaException;
import io.github.alanjoffre.ledgerly.pix.dominio.TransferenciaNaoEncontradaException;
import io.github.alanjoffre.ledgerly.pix.dominio.TransferenciaForaDeAnaliseException;
import io.github.alanjoffre.ledgerly.pix.dominio.TransferenciaPix;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Caso de uso "fazer um Pix": por chave ou por QR Code (copia e cola).
 *
 * <p>Roteiro:
 * <ol>
 *   <li><b>trava rápida no Redis</b> pela chave de idempotência: o segundo clique, enquanto o
 *       primeiro ainda corre, recebe 409 na hora, sem chegar ao banco;</li>
 *   <li><b>pedido repetido?</b> se a chave já tem transferência, devolve a mesma (é o
 *       {@code UNIQUE} do banco que garante, com ou sem Redis);</li>
 *   <li>resolve o recebedor pela chave e o pagador pelo serviço de contas;</li>
 *   <li>grava a transferência (situação {@code CRIADA}) e executa a Saga na hora — no caminho
 *       feliz, a resposta já sai {@code CONCLUIDA}.</li>
 * </ol>
 *
 * <p><b>Autorização por objeto</b> (ADR-0026): o cliente só paga com a conta dele e só consulta o
 * Pix que ele fez. A conta (ou o Pix) de outro cliente responde igual a uma que não existe — e a
 * conferência vem antes de tudo, até do pedido repetido, para a chave de idempotência de outra
 * pessoa não virar um jeito de ler o Pix dela.
 */
@ApplicationScoped
public class TransferenciaService {

    /** Quanto a trava rápida dura, no máximo, se o pedido morrer sem liberar. */
    static final Duration VALIDADE_DA_TRAVA = Duration.ofSeconds(30);

    private final TransferenciaRepository transferencias;
    private final ChaveRepository chaves;
    private final Contas contas;
    private final TravaDePedido trava;
    private final RegistroDaSaga registro;
    private final OrquestradorDaSaga orquestrador;
    private final Clock relogio;

    public TransferenciaService(
            TransferenciaRepository transferencias,
            ChaveRepository chaves,
            Contas contas,
            TravaDePedido trava,
            RegistroDaSaga registro,
            OrquestradorDaSaga orquestrador,
            Clock relogio) {
        this.transferencias = transferencias;
        this.chaves = chaves;
        this.contas = contas;
        this.trava = trava;
        this.registro = registro;
        this.orquestrador = orquestrador;
        this.relogio = relogio;
    }

    /**
     * O pedido de Pix. Informe {@code chave} OU {@code copiaECola}. O {@code valor} é obrigatório
     * com chave; com QR Code, vale o do QR Code (se ele tiver um).
     */
    public record Pedido(
            String chaveIdempotencia,
            UUID pagadorContaId,
            String chave,
            String copiaECola,
            Dinheiro valor,
            String descricao) {}

    /** @param repetido a chave de idempotência já tinha sido usada para este mesmo pedido */
    public record Resultado(TransferenciaPix transferencia, boolean repetido) {}

    private record Destino(ChavePix chave, Dinheiro valor, String descricao) {}

    public Resultado iniciar(Pedido pedido, Acesso acesso) {
        Destino destino = resolverDestino(pedido);
        Contas.ContaResumo pagadorConferido = acesso.semDono() ? null : contaDoDono(pedido.pagadorContaId(), acesso);
        String chaveDaTrava = "pix:pedido:" + pedido.chaveIdempotencia();
        if (!trava.travar(chaveDaTrava, VALIDADE_DA_TRAVA)) {
            throw new PedidoEmAndamentoException(
                    "Já há um pedido com a chave '%s' em andamento".formatted(pedido.chaveIdempotencia()));
        }
        try {
            Optional<Resultado> repetido = repetido(pedido, destino);
            if (repetido.isPresent()) {
                return repetido.get();
            }
            ChaveRegistrada recebedor = chaves.buscar(destino.chave())
                    .orElseThrow(() -> new ChaveNaoEncontradaException(
                            "Nenhuma conta tem a chave " + destino.chave().mascarada()));
            Contas.ContaResumo pagador = pagadorConferido != null
                    ? pagadorConferido
                    : contas.buscar(pedido.pagadorContaId()).orElseThrow(() -> contaNaoEncontrada(pedido.pagadorContaId()));
            if (!pagador.ativa()) {
                throw new ContaInativaException("A conta pagadora não está ativa");
            }

            TransferenciaPix nova = TransferenciaPix.criar(
                    pedido.chaveIdempotencia(),
                    new Participante(pagador.contaId(), pagador.titularNome()),
                    new Participante(recebedor.contaId(), recebedor.titularNome()),
                    recebedor.chave(),
                    destino.valor(),
                    destino.descricao(),
                    relogio.instant());
            if (!registro.inserir(nova)) {
                // Corrida: outro pedido com a mesma chave gravou primeiro (o Redis estava fora, ou a
                // trava venceu). O UNIQUE do banco decidiu; este vira um pedido repetido.
                return repetido(pedido, destino).orElseThrow();
            }
            // Já reservada para este pedido: o agendador não a leva no meio (ADR-0031).
            orquestrador.continuar(nova);
            return new Resultado(consultar(nova.id()), false);
        } finally {
            trava.liberar(chaveDaTrava);
        }
    }

    public TransferenciaPix consultar(UUID id) {
        return transferencias.buscar(id).orElseThrow(() -> transferenciaNaoEncontrada(id));
    }

    /** Como {@link #consultar(UUID)}, se quem pede é o dono da conta que pagou (ou lê tudo). */
    public TransferenciaPix consultar(UUID id, Acesso acesso) {
        TransferenciaPix transferencia = consultar(id);
        if (!acesso.semDono()) {
            boolean doDono = contas.buscar(transferencia.pagador().contaId())
                    .filter(conta -> acesso.podeUsar(conta.clienteId()))
                    .isPresent();
            if (!doDono) {
                throw transferenciaNaoEncontrada(id);
            }
        }
        return transferencia;
    }

    /** A fila do backoffice: os Pix em análise, do mais antigo para o mais novo. */
    public List<TransferenciaPix> emAnalise(int limite) {
        return transferencias.listarPorSituacao(SituacaoTransferencia.EM_ANALISE, limite);
    }

    /** O pedido de devolução de um Pix em análise, com quem decidiu e por quê. */
    public record PedidoDeDevolucao(
            UUID transferenciaId, UUID contaDestinoId, String justificativa, String analistaId, String analistaNome) {}

    /**
     * A decisão do analista para um Pix em análise (ADR-0030): devolver o dinheiro para outra conta
     * do mesmo titular, ativa. A decisão é gravada (auditoria) na mesma transação em que a Saga
     * volta a andar; o estorno sai logo em seguida, como no Pix novo.
     */
    public TransferenciaPix devolver(PedidoDeDevolucao pedido) {
        DecisaoDeAnalise decisao = new DecisaoDeAnalise(
                UUID.randomUUID(),
                pedido.transferenciaId(),
                pedido.analistaId(),
                pedido.analistaNome(),
                DecisaoDeAnalise.Decisao.DEVOLVER,
                pedido.contaDestinoId(),
                pedido.justificativa(),
                relogio.instant());
        TransferenciaPix transferencia = consultar(pedido.transferenciaId());
        if (transferencia.situacao() != SituacaoTransferencia.EM_ANALISE) {
            throw new TransferenciaForaDeAnaliseException("A transferência %s não está em análise (situação %s)"
                    .formatted(transferencia.id(), transferencia.situacao()));
        }
        Contas.ContaResumo pagador = contas.buscar(transferencia.pagador().contaId())
                .orElseThrow(() -> contaNaoEncontrada(transferencia.pagador().contaId()));
        Contas.ContaResumo destino = contas.buscar(pedido.contaDestinoId())
                .orElseThrow(() -> contaNaoEncontrada(pedido.contaDestinoId()));
        if (!destino.clienteId().equals(pagador.clienteId())) {
            throw new ContaDeOutroTitularException("A conta %s não é do titular que pagou o Pix".formatted(destino.contaId()));
        }
        if (!destino.ativa()) {
            throw new ContaInativaException("A conta %s não está ativa".formatted(destino.contaId()));
        }
        orquestrador.continuar(registro.decidirDevolucao(decisao));
        return consultar(transferencia.id());
    }

    private Contas.ContaResumo contaDoDono(UUID contaId, Acesso acesso) {
        return contas.buscar(contaId)
                .filter(conta -> acesso.podeUsar(conta.clienteId()))
                .orElseThrow(() -> contaNaoEncontrada(contaId));
    }

    private static ContaNaoEncontradaException contaNaoEncontrada(UUID contaId) {
        return new ContaNaoEncontradaException("Conta %s não encontrada".formatted(contaId));
    }

    private static TransferenciaNaoEncontradaException transferenciaNaoEncontrada(UUID id) {
        return new TransferenciaNaoEncontradaException("Transferência %s não encontrada".formatted(id));
    }

    private Optional<Resultado> repetido(Pedido pedido, Destino destino) {
        return transferencias.buscarPorChaveDeIdempotencia(pedido.chaveIdempotencia()).map(existente -> {
            if (!existente.correspondeA(pedido.pagadorContaId(), destino.chave(), destino.valor())) {
                throw new ChaveIdempotenciaReutilizadaException(
                        "A chave de idempotência '%s' já foi usada para outro pedido".formatted(pedido.chaveIdempotencia()));
            }
            return new Resultado(existente, true);
        });
    }

    private static Destino resolverDestino(Pedido pedido) {
        boolean temChave = pedido.chave() != null && !pedido.chave().isBlank();
        boolean temQrCode = pedido.copiaECola() != null && !pedido.copiaECola().isBlank();
        if (temChave == temQrCode) {
            throw new TransferenciaInvalidaException("Informe a chave OU o QR Code (copia e cola), um dos dois");
        }
        if (temChave) {
            if (pedido.valor() == null) {
                throw new TransferenciaInvalidaException("Informe o valor do Pix");
            }
            return new Destino(ChavePix.identificar(pedido.chave()), pedido.valor(), pedido.descricao());
        }
        BrCode qrCode = BrCode.ler(pedido.copiaECola());
        Dinheiro valor = qrCode.valorFixo().orElse(pedido.valor());
        if (valor == null) {
            throw new TransferenciaInvalidaException("O QR Code não tem valor: informe o valor do Pix");
        }
        if (pedido.valor() != null && !pedido.valor().equals(valor)) {
            throw new TransferenciaInvalidaException(
                    "O QR Code é de %s, mas o pedido é de %s".formatted(valor, pedido.valor()));
        }
        String descricao = pedido.descricao() != null ? pedido.descricao() : qrCode.descricao();
        return new Destino(qrCode.chave(), valor, descricao);
    }
}
