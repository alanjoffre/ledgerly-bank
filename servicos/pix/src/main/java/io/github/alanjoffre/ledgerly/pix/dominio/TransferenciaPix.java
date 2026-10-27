package io.github.alanjoffre.ledgerly.pix.dominio;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Uma transferência Pix, e a Saga que a leva até o fim (ADR-0015).
 *
 * <p><b>Saga orquestrada:</b> o dinheiro passa por mais de um passo, cada um uma transação
 * separada no razão — não existe uma transação de banco que cubra os dois serviços. Esta classe é
 * a memória da Saga: sabe em que passo está, qual é o próximo e o que fazer com cada resposta.
 * Quem chama o razão é o orquestrador (na camada de aplicação); aqui só ficam as regras.
 *
 * <p><b>Padrão:</b> entidade e raiz de agregado (DDD) — toda mudança passa por
 * {@link #aplicar(Passo, ResultadoDoPasso, Instant)}, que recusa transição fora de ordem.
 */
public final class TransferenciaPix {

    public static final int TAMANHO_MAXIMO_DESCRICAO = 140;
    public static final int TAMANHO_MAXIMO_CHAVE_IDEMPOTENCIA = 100;

    private final UUID id;
    private final String chaveIdempotencia;
    private final Participante pagador;
    private final Participante recebedor;
    private final ChavePix chaveDoRecebedor;
    private final Dinheiro valor;
    private final String descricao;
    private final Instant criadaEm;
    private SituacaoTransferencia situacao;
    private String motivo;
    private UUID lancamentoEnvioId;
    private UUID lancamentoRecebimentoId;
    private UUID lancamentoEstornoId;
    private int tentativas;
    private Instant proximaTentativaEm;
    private Instant finalizadaEm;

    private TransferenciaPix(
            UUID id,
            String chaveIdempotencia,
            Participante pagador,
            Participante recebedor,
            ChavePix chaveDoRecebedor,
            Dinheiro valor,
            String descricao,
            Instant criadaEm,
            SituacaoTransferencia situacao) {
        this.id = Objects.requireNonNull(id, "id é obrigatório");
        this.chaveIdempotencia = validarChaveIdempotencia(chaveIdempotencia);
        this.pagador = Objects.requireNonNull(pagador, "pagador é obrigatório");
        this.recebedor = Objects.requireNonNull(recebedor, "recebedor é obrigatório");
        this.chaveDoRecebedor = Objects.requireNonNull(chaveDoRecebedor, "a chave do recebedor é obrigatória");
        this.valor = Objects.requireNonNull(valor, "valor é obrigatório");
        this.descricao = descricao;
        this.criadaEm = micros(Objects.requireNonNull(criadaEm, "criadaEm é obrigatório"));
        this.situacao = Objects.requireNonNull(situacao, "situação é obrigatória");
        this.proximaTentativaEm = this.criadaEm;
    }

    /**
     * Cria a transferência, ainda sem mover dinheiro.
     *
     * @throws TransferenciaInvalidaException valor não positivo ou fora do real, pagador e
     *     recebedor na mesma conta, descrição longa demais
     */
    public static TransferenciaPix criar(
            String chaveIdempotencia,
            Participante pagador,
            Participante recebedor,
            ChavePix chaveDoRecebedor,
            Dinheiro valor,
            String descricao,
            Instant agora) {
        Objects.requireNonNull(valor, "valor é obrigatório");
        if (!valor.ehPositivo()) {
            throw new TransferenciaInvalidaException("O valor do Pix deve ser positivo: " + valor);
        }
        if (!valor.moeda().equals(Dinheiro.REAL)) {
            throw new TransferenciaInvalidaException("O Pix é sempre em reais");
        }
        if (pagador.contaId().equals(recebedor.contaId())) {
            throw new TransferenciaInvalidaException("O pagador e o recebedor são a mesma conta");
        }
        if (descricao != null && descricao.length() > TAMANHO_MAXIMO_DESCRICAO) {
            throw new TransferenciaInvalidaException(
                    "A descrição tem mais de %d caracteres".formatted(TAMANHO_MAXIMO_DESCRICAO));
        }
        return new TransferenciaPix(
                UUID.randomUUID(),
                chaveIdempotencia,
                pagador,
                recebedor,
                chaveDoRecebedor,
                valor,
                descricao,
                agora,
                SituacaoTransferencia.CRIADA);
    }

    /** O estado completo, como gravado no banco — usado só para reconstituir. */
    public record Estado(
            UUID id,
            String chaveIdempotencia,
            Participante pagador,
            Participante recebedor,
            ChavePix chaveDoRecebedor,
            Dinheiro valor,
            String descricao,
            SituacaoTransferencia situacao,
            String motivo,
            UUID lancamentoEnvioId,
            UUID lancamentoRecebimentoId,
            UUID lancamentoEstornoId,
            int tentativas,
            Instant proximaTentativaEm,
            Instant criadaEm,
            Instant finalizadaEm) {}

    /** Recria a transferência a partir do que está gravado no banco. */
    public static TransferenciaPix reconstituir(Estado e) {
        var t = new TransferenciaPix(
                e.id(),
                e.chaveIdempotencia(),
                e.pagador(),
                e.recebedor(),
                e.chaveDoRecebedor(),
                e.valor(),
                e.descricao(),
                e.criadaEm(),
                e.situacao());
        t.motivo = e.motivo();
        t.lancamentoEnvioId = e.lancamentoEnvioId();
        t.lancamentoRecebimentoId = e.lancamentoRecebimentoId();
        t.lancamentoEstornoId = e.lancamentoEstornoId();
        t.tentativas = e.tentativas();
        t.proximaTentativaEm = micros(e.proximaTentativaEm());
        t.finalizadaEm = e.finalizadaEm() == null ? null : micros(e.finalizadaEm());
        return t;
    }

    public Estado estado() {
        return new Estado(
                id,
                chaveIdempotencia,
                pagador,
                recebedor,
                chaveDoRecebedor,
                valor,
                descricao,
                situacao,
                motivo,
                lancamentoEnvioId,
                lancamentoRecebimentoId,
                lancamentoEstornoId,
                tentativas,
                proximaTentativaEm,
                criadaEm,
                finalizadaEm);
    }

    /** O próximo passo da Saga, ou vazio se ela já terminou. */
    public Optional<Passo> proximoPasso() {
        return switch (situacao) {
            case CRIADA -> Optional.of(Passo.DEBITAR);
            case DEBITADA -> Optional.of(Passo.CREDITAR);
            case ESTORNANDO -> Optional.of(Passo.ESTORNAR);
            case CONCLUIDA, REJEITADA, ESTORNADA, EM_ANALISE -> Optional.empty();
        };
    }

    /**
     * Aplica a resposta do razão a um passo e muda a situação.
     *
     * @throws IllegalStateException se o passo não é o próximo (resposta atrasada ou fora de ordem)
     */
    public void aplicar(Passo passo, ResultadoDoPasso resultado, Instant agora) {
        Objects.requireNonNull(resultado, "resultado é obrigatório");
        if (proximoPasso().filter(passo::equals).isEmpty()) {
            throw new IllegalStateException(
                    "O passo %s não é o próximo da transferência %s (situação %s)".formatted(passo, id, situacao));
        }
        Instant instante = micros(agora);
        switch (resultado) {
            case ResultadoDoPasso.SemResposta semResposta -> {
                tentativas++;
                proximaTentativaEm = instante.plus(NovasTentativas.espera(tentativas));
                return;
            }
            case ResultadoDoPasso.Feito feito -> aplicarFeito(passo, feito.lancamentoId(), instante);
            case ResultadoDoPasso.Recusado recusado -> aplicarRecusa(passo, recusado.motivo());
        }
        tentativas = 0;
        proximaTentativaEm = instante;
        if (situacao.ehFinal()) {
            finalizadaEm = instante;
        }
    }

    private void aplicarFeito(Passo passo, UUID lancamentoId, Instant agora) {
        switch (passo) {
            case DEBITAR -> {
                lancamentoEnvioId = lancamentoId;
                situacao = SituacaoTransferencia.DEBITADA;
            }
            case CREDITAR -> {
                lancamentoRecebimentoId = lancamentoId;
                situacao = SituacaoTransferencia.CONCLUIDA;
            }
            case ESTORNAR -> {
                lancamentoEstornoId = lancamentoId;
                situacao = SituacaoTransferencia.ESTORNADA;
            }
        }
    }

    private void aplicarRecusa(Passo passo, String motivoDaRecusa) {
        switch (passo) {
            // Débito recusado: nenhum dinheiro saiu. Fim, sem compensação.
            case DEBITAR -> situacao = SituacaoTransferencia.REJEITADA;
            // Crédito recusado: o dinheiro está na Liquidação Pix. Compensar: devolver ao pagador.
            case CREDITAR -> situacao = SituacaoTransferencia.ESTORNANDO;
            // Estorno recusado (por exemplo, o pagador encerrou a conta no meio): o dinheiro ficou
            // na Liquidação Pix e uma pessoa precisa decidir o destino dele.
            case ESTORNAR -> situacao = SituacaoTransferencia.EM_ANALISE;
        }
        motivo = motivo == null ? motivoDaRecusa : motivo + "; estorno recusado: " + motivoDaRecusa;
    }

    /** Pedido repetido com a mesma chave de idempotência: é o mesmo pedido? */
    public boolean correspondeA(UUID pagadorContaId, ChavePix chave, Dinheiro outroValor) {
        return pagador.contaId().equals(pagadorContaId) && chaveDoRecebedor.equals(chave) && valor.equals(outroValor);
    }

    /** Já tentou tantas vezes sem resposta que alguém precisa olhar. */
    public boolean precisaDeAlerta() {
        return tentativas >= NovasTentativas.ALERTA;
    }

    private static String validarChaveIdempotencia(String chave) {
        if (chave == null
                || chave.isBlank()
                || chave.length() > TAMANHO_MAXIMO_CHAVE_IDEMPOTENCIA
                || !chave.chars().allMatch(c -> c > 32 && c < 127)) {
            throw new TransferenciaInvalidaException(
                    "A chave de idempotência deve ter de 1 a %d caracteres visíveis, sem espaço"
                            .formatted(TAMANHO_MAXIMO_CHAVE_IDEMPOTENCIA));
        }
        return chave;
    }

    // O PostgreSQL guarda microssegundos; sem cortar aqui, a transferência na memória e a mesma
    // lida do banco teriam horários diferentes (o mesmo achado da Fase 1).
    private static Instant micros(Instant instante) {
        return instante.truncatedTo(ChronoUnit.MICROS);
    }

    public UUID id() {
        return id;
    }

    public String chaveIdempotencia() {
        return chaveIdempotencia;
    }

    public Participante pagador() {
        return pagador;
    }

    public Participante recebedor() {
        return recebedor;
    }

    public ChavePix chaveDoRecebedor() {
        return chaveDoRecebedor;
    }

    public Dinheiro valor() {
        return valor;
    }

    public String descricao() {
        return descricao;
    }

    public SituacaoTransferencia situacao() {
        return situacao;
    }

    public String motivo() {
        return motivo;
    }

    public UUID lancamentoEnvioId() {
        return lancamentoEnvioId;
    }

    public UUID lancamentoRecebimentoId() {
        return lancamentoRecebimentoId;
    }

    public UUID lancamentoEstornoId() {
        return lancamentoEstornoId;
    }

    public int tentativas() {
        return tentativas;
    }

    public Instant proximaTentativaEm() {
        return proximaTentativaEm;
    }

    public Instant criadaEm() {
        return criadaEm;
    }

    public Instant finalizadaEm() {
        return finalizadaEm;
    }
}
