package io.github.alanjoffre.ledgerly.pix.aplicacao;

import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.Antifraude;
import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.Contas;
import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.Razao;
import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.TransferenciaRepository;
import io.github.alanjoffre.ledgerly.pix.dominio.Passo;
import io.github.alanjoffre.ledgerly.pix.dominio.ResultadoDaAvaliacao;
import io.github.alanjoffre.ledgerly.pix.dominio.ResultadoDoPasso;
import io.github.alanjoffre.ledgerly.pix.dominio.TransferenciaPix;
import jakarta.enterprise.context.ApplicationScoped;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * O orquestrador da Saga do Pix (ADR-0015): leva cada transferência até o fim, um passo de cada vez.
 *
 * <p><b>Saga orquestrada</b> (e não coreografada): um ponto só — este — sabe a ordem dos passos e
 * decide a compensação. Na coreografia, cada serviço reagiria a eventos do outro, e a regra
 * "se o crédito falhar, estorne" ficaria espalhada entre eles.
 *
 * <p>Antes do primeiro passo, o antifraude (ADR-0033): aprovado, segue; retido ou recusado, para
 * aqui, sem nenhum dinheiro ter saído.
 *
 * <p>Reservar (transação curta), e para cada passo: chamar o razão (sem transação aberta) →
 * registrar o resultado e renovar a reserva (transação curta). Quem reservou fica com a
 * transferência até ela terminar ou um passo ficar sem resposta: o agendador não a leva no meio
 * (ADR-0031). Se o serviço morrer em qualquer ponto, nada se perde:
 * <ul>
 *   <li>morreu antes de chamar o razão: a reserva vence e outra execução chama;</li>
 *   <li>morreu depois de o razão mover o dinheiro e antes de registrar: a próxima execução chama
 *       o razão de novo, com a mesma chave de idempotência, e recebe o lançamento original —
 *       nenhum centavo se move duas vezes.</li>
 * </ul>
 */
@ApplicationScoped
public class OrquestradorDaSaga {

    private static final System.Logger LOG = System.getLogger(OrquestradorDaSaga.class.getName());
    private static final int TAMANHO_MAXIMO_DESCRICAO = 140;

    private final RegistroDaSaga registro;
    private final TransferenciaRepository transferencias;
    private final Razao razao;
    private final Antifraude antifraude;
    private final Contas contas;
    private final Clock relogio;

    public OrquestradorDaSaga(
            RegistroDaSaga registro,
            TransferenciaRepository transferencias,
            Razao razao,
            Antifraude antifraude,
            Contas contas,
            Clock relogio) {
        this.registro = registro;
        this.transferencias = transferencias;
        this.razao = razao;
        this.antifraude = antifraude;
        this.contas = contas;
        this.relogio = relogio;
    }

    /**
     * Reserva a transferência e executa os passos (veja {@link #continuar(TransferenciaPix)}). Se
     * ela terminou, outra execução está com ela, ou ainda não é hora, não faz nada.
     */
    public void avancar(UUID id) {
        registro.reservar(id).ifPresent(this::continuar);
    }

    /**
     * Executa os passos de uma transferência que já está reservada para quem chama (acabou de ser
     * criada, ou de ter a devolução decidida), até ela terminar ou um passo ficar sem resposta (aí
     * ela volta mais tarde, pela {@link #retomarPendentes()}).
     */
    public void continuar(TransferenciaPix transferencia) {
        if (transferencia.precisaDeAvaliacao() && !registro.registrarAvaliacao(transferencia, avaliar(transferencia))) {
            return; // retida ou recusada: nenhum dinheiro saiu, e a reserva já foi solta
        }
        // No máximo três passos: debitar, creditar e (se o crédito for recusado) estornar.
        for (int i = 0; i < Passo.values().length; i++) {
            Passo passo = transferencia.proximoPasso().orElseThrow();
            ResultadoDoPasso resultado = razao.executar(
                    passo,
                    transferencia.chaveDoPasso(passo),
                    transferencia.contaDoPasso(passo),
                    transferencia.valor(),
                    descricao(passo, transferencia),
                    passo == Passo.DEBITAR ? null : transferencia.lancamentoEnvioId());
            boolean segue = registro.registrar(transferencia, passo, resultado);

            if (resultado instanceof ResultadoDoPasso.SemResposta semResposta) {
                Level nivel = transferencia.precisaDeAlerta() ? Level.WARNING : Level.INFO;
                LOG.log(nivel, "Pix {0}: {1} sem resposta do razão ({2}); tentativa {3}, próxima em {4}",
                        transferencia.id(), passo, semResposta.erro(), transferencia.tentativas(),
                        transferencia.proximaTentativaEm());
            }
            if (!segue) {
                return; // terminou, ou vai esperar: a reserva já foi solta
            }
        }
    }

    /**
     * Retoma as Sagas paradas no meio: passo sem resposta cuja hora de repetir chegou, ou uma
     * execução que morreu com a reserva na mão. Chamado de tempos em tempos por um agendador.
     *
     * @return quantas transferências foram retomadas
     */
    public int retomarPendentes() {
        List<UUID> prontas = transferencias.prontasParaRetomar(relogio.instant(), 50);
        prontas.forEach(this::avancar);
        return prontas.size();
    }

    /**
     * Pergunta ao antifraude. A abertura da conta recebedora vem do serviço de contas; sem ela (contas
     * fora do ar), também vale a falha segura — fica registrada como "sem resposta".
     */
    private ResultadoDaAvaliacao avaliar(TransferenciaPix t) {
        Instant abertaEm;
        try {
            abertaEm = contas.buscar(t.recebedor().contaId()).map(Contas.ContaResumo::abertaEm).orElse(null);
        } catch (ServicoIndisponivelException e) {
            abertaEm = null;
        }
        ResultadoDaAvaliacao resultado = abertaEm == null
                ? new ResultadoDaAvaliacao.SemResposta("sem a abertura da conta recebedora")
                : antifraude.avaliar(new Antifraude.Pedido(
                        t.id(), t.pagador().contaId(), t.recebedor().contaId(), t.valor(), t.criadaEm(), abertaEm));
        if (resultado instanceof ResultadoDaAvaliacao.SemResposta semResposta) {
            LOG.log(Level.WARNING, "Pix {0}: antifraude sem resposta ({1}); vale a falha segura por valor",
                    t.id(), semResposta.erro());
        }
        return resultado;
    }

    /**
     * O texto que aparece no extrato contábil do razão para cada passo. No débito e no crédito vai a
     * mensagem de quem paga ("Aluguel"), que os dois lados veem no extrato; sem mensagem, quem é a
     * outra ponta. O estorno diz sempre o que aconteceu.
     */
    private static String descricao(Passo passo, TransferenciaPix t) {
        boolean temMensagem = t.descricao() != null && !t.descricao().isBlank();
        String texto = switch (passo) {
            case DEBITAR -> temMensagem ? t.descricao() : "Pix para " + t.recebedor().nome();
            case CREDITAR -> temMensagem ? t.descricao() : "Pix de " + t.pagador().nome();
            case ESTORNAR -> t.contaDevolucaoId() == null
                    ? "Estorno do Pix para " + t.recebedor().nome()
                    : "Devolução do Pix para " + t.recebedor().nome() + " (análise)";
        };
        return texto.length() > TAMANHO_MAXIMO_DESCRICAO ? texto.substring(0, TAMANHO_MAXIMO_DESCRICAO) : texto;
    }
}
