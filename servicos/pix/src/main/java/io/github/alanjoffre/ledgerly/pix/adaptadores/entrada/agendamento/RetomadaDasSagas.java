package io.github.alanjoffre.ledgerly.pix.adaptadores.entrada.agendamento;

import io.github.alanjoffre.ledgerly.pix.aplicacao.OrquestradorDaSaga;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Adaptador de entrada: o agendador que acorda o orquestrador a cada segundo para retomar as Sagas
 * paradas no meio (passo sem resposta, ou uma execução que morreu). {@code SKIP}: se a rodada
 * anterior ainda estiver rodando, esta é pulada.
 */
@ApplicationScoped
class RetomadaDasSagas {

    private static final Logger LOG = Logger.getLogger(RetomadaDasSagas.class);

    private final OrquestradorDaSaga orquestrador;

    RetomadaDasSagas(OrquestradorDaSaga orquestrador) {
        this.orquestrador = orquestrador;
    }

    @Scheduled(every = "${pix.saga.intervalo:1s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void retomar() {
        try {
            int retomadas = orquestrador.retomarPendentes();
            if (retomadas > 0) {
                LOG.debugf("%d transferências retomadas", retomadas);
            }
        } catch (RuntimeException e) {
            LOG.warnf("Falha ao retomar as Sagas; nova rodada em instantes: %s", e.getMessage());
        }
    }
}
