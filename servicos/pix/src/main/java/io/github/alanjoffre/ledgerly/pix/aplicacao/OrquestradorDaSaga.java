package io.github.alanjoffre.ledgerly.pix.aplicacao;

import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.Razao;
import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.TransferenciaRepository;
import io.github.alanjoffre.ledgerly.pix.dominio.Passo;
import io.github.alanjoffre.ledgerly.pix.dominio.ResultadoDoPasso;
import io.github.alanjoffre.ledgerly.pix.dominio.TransferenciaPix;
import jakarta.enterprise.context.ApplicationScoped;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * O orquestrador da Saga do Pix (ADR-0015): leva cada transferência até o fim, um passo de cada vez.
 *
 * <p><b>Saga orquestrada</b> (e não coreografada): um ponto só — este — sabe a ordem dos passos e
 * decide a compensação. Na coreografia, cada serviço reagiria a eventos do outro, e a regra
 * "se o crédito falhar, estorne" ficaria espalhada entre eles.
 *
 * <p>Para cada passo: reservar (transação curta) → chamar o razão (sem transação aberta) →
 * registrar o resultado (transação curta). Se o serviço morrer em qualquer ponto, nada se perde:
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
    private final Clock relogio;

    public OrquestradorDaSaga(
            RegistroDaSaga registro, TransferenciaRepository transferencias, Razao razao, Clock relogio) {
        this.registro = registro;
        this.transferencias = transferencias;
        this.razao = razao;
        this.relogio = relogio;
    }

    /**
     * Executa os passos da transferência até ela terminar ou um passo ficar sem resposta (aí ela
     * volta mais tarde, pela {@link #retomarPendentes()}).
     */
    public void avancar(UUID id) {
        // No máximo três passos: debitar, creditar e (se o crédito for recusado) estornar.
        for (int i = 0; i < Passo.values().length; i++) {
            var reservada = registro.reservar(id);
            if (reservada.isEmpty()) {
                return; // terminou, outra execução está com ela, ou ainda não é hora
            }
            TransferenciaPix transferencia = reservada.get();
            Passo passo = transferencia.proximoPasso().orElseThrow();
            ResultadoDoPasso resultado = razao.executar(
                    passo,
                    transferencia.chaveDoPasso(passo),
                    transferencia.contaDoPasso(passo),
                    transferencia.valor(),
                    descricao(passo, transferencia),
                    passo == Passo.DEBITAR ? null : transferencia.lancamentoEnvioId());
            registro.registrar(transferencia, passo, resultado);

            if (resultado instanceof ResultadoDoPasso.SemResposta semResposta) {
                Level nivel = transferencia.precisaDeAlerta() ? Level.WARNING : Level.INFO;
                LOG.log(nivel, "Pix {0}: {1} sem resposta do razão ({2}); tentativa {3}, próxima em {4}",
                        id, passo, semResposta.erro(), transferencia.tentativas(), transferencia.proximaTentativaEm());
                return;
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
