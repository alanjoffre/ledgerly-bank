package io.github.alanjoffre.ledgerly.pix.adaptadores.saida.contas;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import io.quarkus.oidc.client.filter.OidcClientFilter;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** Cliente HTTP declarativo do serviço de contas. Endereço e tempos-limite em {@code quarkus.rest-client.contas.*}. */
@RegisterRestClient(configKey = "contas")
// O token do Pix (client credentials) vai em toda chamada; o outro lado exige o papel "servico".
@OidcClientFilter
@Path("/v1/contas")
public interface ContasCliente {

    record PedidoDeTitularidade(String cpf) {}

    /** Titular e situação da conta, sem o saldo — não depende do razão estar no ar. */
    @GET
    @Path("/{id}/titular")
    Response titular(@PathParam("id") UUID id);

    @POST
    @Path("/{id}/titularidade")
    Response titularidade(@PathParam("id") UUID id, PedidoDeTitularidade corpo);
}
