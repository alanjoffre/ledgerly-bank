package io.github.alanjoffre.ledgerly.pix.dominio;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
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
    /** O tamanho da coluna {@code motivo}: os motivos do antifraude podem ser mais longos. */
    static final int TAMANHO_MAXIMO_MOTIVO = 400;

    /**
     * Quanto tempo um "conta não encontrada" no crédito vale como "ainda não sei" (ADR-0015).
     *
     * <p>O razão só conhece a conta depois de consumir o evento {@code ContaAberta} (ADR-0014), e a
     * chave Pix pode ser registrada antes disso. Nesse intervalo, o 404 do razão não é certeza de
     * nada: a conta existe no serviço de contas e vai chegar. Estornar seria recusar um Pix
     * legítimo. Passada a janela, o 404 volta a ser recusa e o dinheiro volta ao pagador.
     */
    public static final Duration JANELA_CONTA_NOVA = Duration.ofMinutes(5);
    static final String CONTA_NAO_ENCONTRADA = "conta-nao-encontrada";
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    /**
     * A falha segura (ADR-0033): com o antifraude fora do ar ou lento, o Pix até este valor segue sem
     * avaliação; acima dele, fica retido para uma pessoa. Parar todo Pix porque um serviço caiu
     * seria tirar o banco do ar; deixar passar tudo seria abrir a porta justamente quando ninguém
     * está olhando.
     */
    public static final Dinheiro LIMITE_SEM_ANTIFRAUDE = Dinheiro.reais("500.00");

    /** O começo dos motivos que vêm da análise de risco: é por eles que {@link #barradoNaAnaliseDeRisco()} os reconhece. */
    private static final String RETIDO_PELO_ANTIFRAUDE = "Retido pelo antifraude";
    private static final String RECUSADO_PELO_ANTIFRAUDE = "Recusado pelo antifraude";
    private static final String RETIDO_SEM_AVALIACAO = "Retido sem avaliação";
    private static final String RECUSADO_NA_ANALISE = "Recusado na análise. ";

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
    private Integer notaDeRisco;
    private UUID lancamentoEnvioId;
    private UUID lancamentoRecebimentoId;
    private UUID lancamentoEstornoId;
    private UUID contaDevolucaoId;
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
            Integer notaDeRisco,
            UUID lancamentoEnvioId,
            UUID lancamentoRecebimentoId,
            UUID lancamentoEstornoId,
            UUID contaDevolucaoId,
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
        t.notaDeRisco = e.notaDeRisco();
        t.lancamentoEnvioId = e.lancamentoEnvioId();
        t.lancamentoRecebimentoId = e.lancamentoRecebimentoId();
        t.lancamentoEstornoId = e.lancamentoEstornoId();
        t.contaDevolucaoId = e.contaDevolucaoId();
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
                notaDeRisco,
                lancamentoEnvioId,
                lancamentoRecebimentoId,
                lancamentoEstornoId,
                contaDevolucaoId,
                tentativas,
                proximaTentativaEm,
                criadaEm,
                finalizadaEm);
    }

    /** Ainda não passou pelo antifraude: a avaliação vem antes de qualquer passo no razão. */
    public boolean precisaDeAvaliacao() {
        return situacao == SituacaoTransferencia.CRIADA;
    }

    /**
     * O próximo passo da Saga no razão, ou vazio se ela já terminou, está retida ou ainda precisa da
     * avaliação ({@link #precisaDeAvaliacao()}).
     */
    public Optional<Passo> proximoPasso() {
        return switch (situacao) {
            case APROVADA -> Optional.of(Passo.DEBITAR);
            case DEBITADA -> Optional.of(Passo.CREDITAR);
            case ESTORNANDO -> Optional.of(Passo.ESTORNAR);
            case CRIADA, RETIDA, CONCLUIDA, REJEITADA, ESTORNADA, EM_ANALISE -> Optional.empty();
        };
    }

    /**
     * Aplica a resposta do antifraude (ADR-0033). Sem resposta, decide a regra de valor: até
     * {@link #LIMITE_SEM_ANTIFRAUDE} segue sem nota; acima, retém.
     *
     * @throws IllegalStateException se a transferência já foi avaliada
     */
    public void aplicarAvaliacao(ResultadoDaAvaliacao resultado, Instant agora) {
        Objects.requireNonNull(resultado, "resultado é obrigatório");
        if (!precisaDeAvaliacao()) {
            throw new IllegalStateException(
                    "A transferência %s já foi avaliada (situação %s)".formatted(id, situacao));
        }
        Instant instante = micros(agora);
        switch (resultado) {
            case ResultadoDaAvaliacao.Aprovar aprovar -> {
                notaDeRisco = aprovar.nota();
                situacao = SituacaoTransferencia.APROVADA;
            }
            case ResultadoDaAvaliacao.Segurar segurar -> {
                notaDeRisco = segurar.nota();
                situacao = SituacaoTransferencia.RETIDA;
                motivo = limitar((RETIDO_PELO_ANTIFRAUDE + " (nota %d): %s").formatted(segurar.nota(), segurar.motivo()));
            }
            case ResultadoDaAvaliacao.Recusar recusar -> {
                notaDeRisco = recusar.nota();
                situacao = SituacaoTransferencia.REJEITADA;
                motivo = limitar((RECUSADO_PELO_ANTIFRAUDE + " (nota %d): %s").formatted(recusar.nota(), recusar.motivo()));
            }
            case ResultadoDaAvaliacao.SemResposta semResposta -> {
                if (valor.ehMaiorQue(LIMITE_SEM_ANTIFRAUDE)) {
                    situacao = SituacaoTransferencia.RETIDA;
                    motivo = limitar((RETIDO_SEM_AVALIACAO + ": o valor passa de %s e o antifraude não respondeu (%s)")
                            .formatted(LIMITE_SEM_ANTIFRAUDE.formatar(PT_BR), semResposta.erro()));
                } else {
                    situacao = SituacaoTransferencia.APROVADA;
                }
            }
        }
        proximaTentativaEm = instante;
        if (situacao.ehFinal()) {
            finalizadaEm = instante;
        }
    }

    /**
     * A analista liberou o Pix retido: a Saga volta a andar, e o próximo passo é o débito.
     *
     * @throws TransferenciaForaDeAnaliseException se a transferência não está retida
     */
    public void liberar(Instant agora) {
        exigirRetida();
        situacao = SituacaoTransferencia.APROVADA;
        proximaTentativaEm = micros(agora);
        finalizadaEm = null;
    }

    /**
     * A analista recusou o Pix retido: fim, sem dinheiro movido.
     *
     * @throws TransferenciaForaDeAnaliseException se a transferência não está retida
     */
    public void recusarNaAnalise(Instant agora) {
        exigirRetida();
        situacao = SituacaoTransferencia.REJEITADA;
        motivo = limitar(RECUSADO_NA_ANALISE + motivo);
        finalizadaEm = micros(agora);
    }

    private void exigirRetida() {
        if (situacao != SituacaoTransferencia.RETIDA) {
            throw new TransferenciaForaDeAnaliseException(
                    "A transferência %s não está retida (situação %s)".formatted(id, situacao));
        }
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
        if (passo == Passo.CREDITAR
                && resultado instanceof ResultadoDoPasso.Recusado recusado
                && CONTA_NAO_ENCONTRADA.equals(recusado.codigo())
                && instante.isBefore(criadaEm.plus(JANELA_CONTA_NOVA))) {
            resultado = new ResultadoDoPasso.SemResposta("o razão ainda não conhece a conta de destino");
        }
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

    /**
     * A decisão do analista para um Pix em análise (ADR-0030): devolver o dinheiro, que está na
     * Liquidação Pix, para outra conta do mesmo titular — a que pagou foi encerrada. A Saga volta a
     * andar: o próximo passo é o estorno, agora para a conta decidida.
     *
     * <p>Quem confere "mesmo titular" e "conta ativa" é a aplicação, que fala com o serviço de
     * contas; aqui ficam as regras do próprio Pix.
     *
     * @throws TransferenciaForaDeAnaliseException se a transferência não está em análise
     * @throws TransferenciaInvalidaException se a conta decidida é a que pagou ou a que recebeu
     */
    public void decidirDevolucao(UUID contaDestinoId, Instant agora) {
        Objects.requireNonNull(contaDestinoId, "a conta da devolução é obrigatória");
        if (situacao != SituacaoTransferencia.EM_ANALISE) {
            throw new TransferenciaForaDeAnaliseException(
                    "A transferência %s não está em análise (situação %s)".formatted(id, situacao));
        }
        if (contaDestinoId.equals(pagador.contaId()) || contaDestinoId.equals(recebedor.contaId())) {
            throw new TransferenciaInvalidaException(
                    "A devolução vai para outra conta do titular: não para a que pagou nem para a que recebeu");
        }
        contaDevolucaoId = contaDestinoId;
        situacao = SituacaoTransferencia.ESTORNANDO;
        tentativas = 0;
        proximaTentativaEm = micros(agora);
        finalizadaEm = null;
    }

    /** A conta que o passo movimenta: o recebedor no crédito; no estorno, a da devolução, se houver. */
    public UUID contaDoPasso(Passo passo) {
        return switch (passo) {
            case DEBITAR -> pagador.contaId();
            case CREDITAR -> recebedor.contaId();
            case ESTORNAR -> contaDevolucaoId != null ? contaDevolucaoId : pagador.contaId();
        };
    }

    /**
     * A chave de idempotência do passo no razão. A devolução tem a sua, com a conta decidida: é
     * outro pedido (outra conta), e repetir a mesma decisão continua movendo o dinheiro uma vez só.
     */
    public String chaveDoPasso(Passo passo) {
        if (passo == Passo.ESTORNAR && contaDevolucaoId != null) {
            return "pix:" + id + ":devolucao:" + contaDevolucaoId;
        }
        return passo.chaveDeIdempotencia(id);
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

    private static String limitar(String texto) {
        return texto.length() <= TAMANHO_MAXIMO_MOTIVO ? texto : texto.substring(0, TAMANHO_MAXIMO_MOTIVO - 1) + "…";
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

    /**
     * O Pix parou na análise de risco: retido (pelo antifraude ou pela falha segura), recusado pelo
     * antifraude, ou recusado pela analista depois de retido. O motivo, nesses casos, conta a nota e
     * as regras: é da analista, não do cliente (ADR-0033).
     */
    public boolean barradoNaAnaliseDeRisco() {
        return motivo != null
                && (situacao == SituacaoTransferencia.RETIDA || situacao == SituacaoTransferencia.REJEITADA)
                && (motivo.startsWith(RETIDO_PELO_ANTIFRAUDE) || motivo.startsWith(RECUSADO_PELO_ANTIFRAUDE)
                        || motivo.startsWith(RETIDO_SEM_AVALIACAO) || motivo.startsWith(RECUSADO_NA_ANALISE));
    }

    /** A nota do antifraude (0 a 1000); {@code null} se ainda não avaliada ou se ele não respondeu. */
    public Integer notaDeRisco() {
        return notaDeRisco;
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

    /** A conta do mesmo titular para onde o analista decidiu devolver; {@code null} se não houve devolução. */
    public UUID contaDevolucaoId() {
        return contaDevolucaoId;
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
