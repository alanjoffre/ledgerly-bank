package io.github.alanjoffre.ledgerly.pix.adaptadores.saida.antifraude;

import io.quarkus.oidc.client.filter.OidcClientFilter;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** Cliente HTTP declarativo do antifraude. Endereço e tempos-limite em {@code quarkus.rest-client.antifraude.*}. */
@RegisterRestClient(configKey = "antifraude")
// O token do Pix (client credentials) vai em toda chamada; o antifraude exige o papel "servico".
@OidcClientFilter
@Path("/v1/avaliacoes")
public interface AntifraudeCliente {

    /** Dinheiro como texto ({@code "10.50"}) e instantes ISO-8601, como em toda a API (ADR-0012). */
    record PedidoDeAvaliacao(
            UUID transferenciaId,
            UUID pagadorContaId,
            UUID recebedorContaId,
            String valor,
            String instante,
            String contaRecebedoraAbertaEm) {}

    @POST
    Response avaliar(PedidoDeAvaliacao corpo);
}
