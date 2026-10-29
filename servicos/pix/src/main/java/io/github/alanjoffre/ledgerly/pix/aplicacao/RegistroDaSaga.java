package io.github.alanjoffre.ledgerly.pix.aplicacao;

import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.AuditoriaDeAnalise;
import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.CaixaDeSaida;
import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.TransferenciaRepository;
import io.github.alanjoffre.ledgerly.pix.dominio.DecisaoDeAnalise;
import io.github.alanjoffre.ledgerly.pix.dominio.Passo;
import io.github.alanjoffre.ledgerly.pix.dominio.ResultadoDoPasso;
import io.github.alanjoffre.ledgerly.pix.dominio.SituacaoTransferencia;
import io.github.alanjoffre.ledgerly.pix.dominio.TransferenciaNaoEncontradaException;
import io.github.alanjoffre.ledgerly.pix.dominio.TransferenciaPix;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * As transações curtas da Saga, cada uma no banco do Pix: criar, reservar e registrar o resultado
 * de um passo.
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
     */
    @Transactional
    public void decidirDevolucao(DecisaoDeAnalise decisao) {
        TransferenciaPix transferencia = transferencias.buscarTravando(decisao.transferenciaId())
                .orElseThrow(() -> new TransferenciaNaoEncontradaException(
                        "Transferência %s não encontrada".formatted(decisao.transferenciaId())));
        transferencia.decidirDevolucao(decisao.contaDestinoId(), relogio.instant());
        transferencias.salvar(transferencia);
        auditoria.registrar(decisao);
    }

    @Transactional
    public boolean inserir(TransferenciaPix transferencia) {
        return transferencias.inserir(transferencia);
    }

    @Transactional
    public Optional<TransferenciaPix> reservar(UUID id) {
        return transferencias.reservar(id, relogio.instant(), RESERVA);
    }

    /**
     * Aplica a resposta do razão e grava — e, se a Saga terminou com dinheiro movido, grava o evento
     * na mesma transação (Outbox): ou os dois, ou nenhum.
     */
    @Transactional
    public void registrar(TransferenciaPix transferencia, Passo passo, ResultadoDoPasso resultado) {
        transferencia.aplicar(passo, resultado, relogio.instant());
        transferencias.salvar(transferencia);
        if (transferencia.situacao() == SituacaoTransferencia.CONCLUIDA) {
            caixaDeSaida.pixConcluido(transferencia);
        } else if (transferencia.situacao() == SituacaoTransferencia.ESTORNADA) {
            caixaDeSaida.pixEstornado(transferencia);
        }
    }
}
