package io.github.alanjoffre.ledgerly.pix.adaptadores.saida.antifraude;

import io.smallrye.faulttolerance.api.CircuitBreakerName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * A chamada HTTP ao antifraude, atrás de um circuit breaker (ADR-0041).
 *
 * <p>Fica numa classe própria porque o interceptador só age em chamada de fora do bean. Conta como
 * falha só o que diz que o outro lado <b>não está atendendo</b>: tempo esgotado, conexão recusada e
 * 5xx. Um 4xx ou um corpo estranho são respondidos na hora: abrir o circuito não pouparia espera.
 */
@ApplicationScoped
class ChamadaAoAntifraude {

    /** O antifraude não está atendendo. */
    static final class AntifraudeIndisponivel extends RuntimeException {
        private static final long serialVersionUID = 1L;

        AntifraudeIndisponivel(String erro) {
            super(erro);
        }
    }

    record Resposta(int status, String corpo) {}

    private final AntifraudeCliente cliente;

    ChamadaAoAntifraude(@RestClient AntifraudeCliente cliente) {
        this.cliente = cliente;
    }

    /**
     * Metade das últimas 20 chamadas falhou: por 10 s nenhuma sai, e cada Pix vai direto para a falha
     * segura. Depois, chamadas de teste; 3 certas fecham o circuito, e 1 errada o abre de novo.
     */
    @CircuitBreaker(
            requestVolumeThreshold = 20,
            failureRatio = 0.5,
            delay = 10_000,
            successThreshold = 3,
            failOn = AntifraudeIndisponivel.class)
    @CircuitBreakerName("antifraude")
    Resposta chamar(AntifraudeCliente.PedidoDeAvaliacao corpo) {
        try (Response resposta = cliente.avaliar(corpo)) {
            int status = resposta.getStatus();
            if (status >= 500) {
                throw new AntifraudeIndisponivel("HTTP " + status);
            }
            return new Resposta(status, status == 200 ? resposta.readEntity(String.class) : "");
        } catch (ProcessingException e) {
            throw new AntifraudeIndisponivel(causaRaiz(e));
        }
    }

    private static String causaRaiz(Throwable e) {
        Throwable causa = e;
        while (causa.getCause() != null) {
            causa = causa.getCause();
        }
        return causa.getClass().getSimpleName() + (causa.getMessage() == null ? "" : ": " + causa.getMessage());
    }
}
