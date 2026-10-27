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
import io.github.alanjoffre.ledgerly.pix.dominio.ContaInativaException;
import io.github.alanjoffre.ledgerly.pix.dominio.ContaNaoEncontradaException;
import io.github.alanjoffre.ledgerly.pix.dominio.Participante;
import io.github.alanjoffre.ledgerly.pix.dominio.TransferenciaInvalidaException;
import io.github.alanjoffre.ledgerly.pix.dominio.TransferenciaNaoEncontradaException;
import io.github.alanjoffre.ledgerly.pix.dominio.TransferenciaPix;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;
import java.time.Duration;
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

    public Resultado iniciar(Pedido pedido) {
        Destino destino = resolverDestino(pedido);
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
            Contas.ContaResumo pagador = contas.buscar(pedido.pagadorContaId())
                    .orElseThrow(() -> new ContaNaoEncontradaException(
                            "Conta %s não encontrada".formatted(pedido.pagadorContaId())));
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
            orquestrador.avancar(nova.id());
            return new Resultado(consultar(nova.id()), false);
        } finally {
            trava.liberar(chaveDaTrava);
        }
    }

    public TransferenciaPix consultar(UUID id) {
        return transferencias.buscar(id)
                .orElseThrow(() -> new TransferenciaNaoEncontradaException("Transferência %s não encontrada".formatted(id)));
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
