package io.github.alanjoffre.ledgerly.pix.aplicacao;

import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.CaixaDeSaida;
import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.TransferenciaRepository;
import io.github.alanjoffre.ledgerly.pix.dominio.Passo;
import io.github.alanjoffre.ledgerly.pix.dominio.ResultadoDoPasso;
import io.github.alanjoffre.ledgerly.pix.dominio.SituacaoTransferencia;
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
    private final Clock relogio;

    public RegistroDaSaga(TransferenciaRepository transferencias, CaixaDeSaida caixaDeSaida, Clock relogio) {
        this.transferencias = transferencias;
        this.caixaDeSaida = caixaDeSaida;
        this.relogio = relogio;
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
