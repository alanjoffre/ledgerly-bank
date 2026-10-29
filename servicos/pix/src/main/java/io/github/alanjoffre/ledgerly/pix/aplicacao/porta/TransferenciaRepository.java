package io.github.alanjoffre.ledgerly.pix.aplicacao.porta;

import io.github.alanjoffre.ledgerly.pix.dominio.SituacaoTransferencia;
import io.github.alanjoffre.ledgerly.pix.dominio.TransferenciaPix;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Porta de saída: as transferências e o estado de cada Saga. */
public interface TransferenciaRepository {

    /**
     * Grava a transferência nova, já reservada para quem a criou: o agendador de retomada não a pega
     * enquanto o pedido que a criou executa os passos (ADR-0031).
     *
     * @param reservadaAte até quando quem criou fica com ela
     * @return {@code false} se já existe uma com a mesma chave de idempotência (dois pedidos iguais
     *     ao mesmo tempo: só um grava)
     */
    boolean inserir(TransferenciaPix transferencia, Instant reservadaAte);

    Optional<TransferenciaPix> buscar(UUID id);

    Optional<TransferenciaPix> buscarPorChaveDeIdempotencia(String chave);

    /**
     * Reserva a transferência para executar o próximo passo — só uma execução por vez, mesmo com
     * várias instâncias do serviço. A reserva vence sozinha depois de {@code validade}: se quem
     * reservou morrer no meio, outra execução assume.
     *
     * @return a transferência reservada, ou vazio se ela já terminou, se outra execução já a
     *     reservou, ou se ainda não chegou a hora da próxima tentativa
     */
    Optional<TransferenciaPix> reservar(UUID id, Instant agora, Duration validade);

    /**
     * Grava o novo estado.
     *
     * @param reservadaAte até quando quem executa continua com a transferência (o próximo passo é
     *     agora, e é dela); {@code null} libera a reserva
     */
    void salvar(TransferenciaPix transferencia, Instant reservadaAte);

    /** As transferências numa situação, das mais antigas para as mais novas (a fila do backoffice). */
    List<TransferenciaPix> listarPorSituacao(SituacaoTransferencia situacao, int limite);

    /** Lê a transferência travando a linha até o fim da transação (a decisão de análise). */
    Optional<TransferenciaPix> buscarTravando(UUID id);

    /** As Sagas paradas no meio cuja hora de tentar de novo já chegou. */
    List<UUID> prontasParaRetomar(Instant agora, int limite);
}
