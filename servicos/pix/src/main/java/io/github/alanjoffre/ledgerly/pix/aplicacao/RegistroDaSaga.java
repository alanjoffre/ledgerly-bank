package io.github.alanjoffre.ledgerly.pix.aplicacao;

import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.AuditoriaDeAnalise;
import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.CaixaDeSaida;
import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.TransferenciaRepository;
import io.github.alanjoffre.ledgerly.pix.dominio.DecisaoDeAnalise;
import io.github.alanjoffre.ledgerly.pix.dominio.Passo;
import io.github.alanjoffre.ledgerly.pix.dominio.ResultadoDaAvaliacao;
import io.github.alanjoffre.ledgerly.pix.dominio.ResultadoDoPasso;
import io.github.alanjoffre.ledgerly.pix.dominio.SituacaoTransferencia;
import io.github.alanjoffre.ledgerly.pix.dominio.TransferenciaNaoEncontradaException;
import io.github.alanjoffre.ledgerly.pix.dominio.TransferenciaPix;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * As transações curtas da Saga, cada uma no banco do Pix: criar, reservar e registrar o resultado
 * de um passo.
 *
 * <p>Quem cria a transferência (ou decide a devolução) já sai com a reserva, e a renova a cada
 * passo enquanto o próximo é para agora: o agendador de retomada não a leva no meio do caminho
 * (ADR-0031). A reserva só é solta quando a Saga termina ou um passo fica sem resposta.
 *
 * <p>Ficam separadas do {@link OrquestradorDaSaga} de propósito: a chamada ao razão acontece
 * <b>fora</b> de qualquer transação de banco. Prender uma conexão (e as linhas travadas) enquanto se
 * espera a resposta de outro serviço é o caminho para esgotar o pool sob carga.
 */
@ApplicationScoped
public class RegistroDaSaga {

    /** Quanto tempo uma execução fica com a transferência antes de outra poder assumir. */
    static final Duration RESERVA = Duration.ofSeconds(30);

    private final TransferenciaRepository transferencias;
    private final CaixaDeSaida caixaDeSaida;
    private final AuditoriaDeAnalise auditoria;
    private final Clock relogio;

    public RegistroDaSaga(
            TransferenciaRepository transferencias, CaixaDeSaida caixaDeSaida, AuditoriaDeAnalise auditoria, Clock relogio) {
        this.transferencias = transferencias;
        this.caixaDeSaida = caixaDeSaida;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    /**
     * Aplica a decisão do analista e grava a auditoria — os dois na mesma transação: não existe
     * devolução sem registro de quem decidiu. A linha fica travada: dois analistas decidindo ao
     * mesmo tempo, o segundo encontra a transferência já fora de análise.
     *
     * @return a transferência, já reservada para quem decidiu executar o estorno
     */
    @Transactional
    public TransferenciaPix decidirDevolucao(DecisaoDeAnalise decisao) {
        TransferenciaPix transferencia = transferencias.buscarTravando(decisao.transferenciaId())
                .orElseThrow(() -> new TransferenciaNaoEncontradaException(
                        "Transferência %s não encontrada".formatted(decisao.transferenciaId())));
        Instant agora = relogio.instant();
        transferencia.decidirDevolucao(decisao.contaDestinoId(), agora);
        transferencias.salvar(transferencia, agora.plus(RESERVA));
        auditoria.registrar(decisao);
        return transferencia;
    }

    /**
     * Aplica a resposta do antifraude e grava (ADR-0033).
     *
     * @return {@code true} se o próximo passo é o débito, para agora: a reserva foi renovada e quem
     *     executa segue; {@code false} se ficou retida ou recusada (a reserva foi solta)
     */
    @Transactional
    public boolean registrarAvaliacao(TransferenciaPix transferencia, ResultadoDaAvaliacao resultado) {
        Instant agora = relogio.instant();
        transferencia.aplicarAvaliacao(resultado, agora);
        boolean segue = transferencia.proximoPasso().isPresent();
        transferencias.salvar(transferencia, segue ? agora.plus(RESERVA) : null);
        return segue;
    }

    /**
     * A decisão da analista sobre um Pix retido (liberar ou recusar), com a auditoria na mesma
     * transação — como na devolução. A linha fica travada: duas analistas decidindo ao mesmo tempo,
     * a segunda encontra o Pix já fora da fila.
     *
     * @return a transferência; se liberada, já reservada para quem decidiu executar o débito
     */
    @Transactional
    public TransferenciaPix decidirRetida(DecisaoDeAnalise decisao) {
        TransferenciaPix transferencia = transferencias.buscarTravando(decisao.transferenciaId())
                .orElseThrow(() -> new TransferenciaNaoEncontradaException(
                        "Transferência %s não encontrada".formatted(decisao.transferenciaId())));
        Instant agora = relogio.instant();
        switch (decisao.decisao()) {
            case LIBERAR -> transferencia.liberar(agora);
            case RECUSAR -> transferencia.recusarNaAnalise(agora);
            case DEVOLVER -> throw new IllegalArgumentException("devolução não é decisão de Pix retido");
        }
        transferencias.salvar(transferencia, transferencia.proximoPasso().isPresent() ? agora.plus(RESERVA) : null);
        auditoria.registrar(decisao);
        return transferencia;
    }

    /** Grava a transferência nova, já reservada para quem a criou. */
    @Transactional
    public boolean inserir(TransferenciaPix transferencia) {
        return transferencias.inserir(transferencia, relogio.instant().plus(RESERVA));
    }

    @Transactional
    public Optional<TransferenciaPix> reservar(UUID id) {
        return transferencias.reservar(id, relogio.instant(), RESERVA);
    }

    /**
     * Aplica a resposta do razão e grava — e, se a Saga terminou com dinheiro movido, grava o evento
     * na mesma transação (Outbox): ou os dois, ou nenhum.
     *
     * @return {@code true} se o próximo passo é para agora: a reserva foi renovada e quem executa
     *     segue; {@code false} se a Saga terminou ou vai esperar (a reserva foi solta)
     */
    @Transactional
    public boolean registrar(TransferenciaPix transferencia, Passo passo, ResultadoDoPasso resultado) {
        Instant agora = relogio.instant();
        transferencia.aplicar(passo, resultado, agora);
        boolean segue = transferencia.proximoPasso().isPresent() && !transferencia.proximaTentativaEm().isAfter(agora);
        transferencias.salvar(transferencia, segue ? agora.plus(RESERVA) : null);
        if (transferencia.situacao() == SituacaoTransferencia.CONCLUIDA) {
            caixaDeSaida.pixConcluido(transferencia);
        } else if (transferencia.situacao() == SituacaoTransferencia.ESTORNADA) {
            caixaDeSaida.pixEstornado(transferencia);
        }
        return segue;
    }
}
