package io.github.alanjoffre.ledgerly.pix.adaptadores.saida.razao;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import io.quarkus.oidc.client.filter.OidcClientFilter;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Cliente HTTP declarativo do razão: a interface descreve as chamadas e o Quarkus gera o código.
 * Endereço e tempos-limite em {@code quarkus.rest-client.razao.*}.
 *
 * <p>Devolve {@link Response} cru, sem virar exceção nos erros: quem decide o que cada status
 * significa para a Saga é o {@link HttpRazao}.
 */
@RegisterRestClient(configKey = "razao")
// O token do Pix (client credentials) vai em toda chamada; o outro lado exige o papel "servico".
@OidcClientFilter
@Path("/v1/pix")
public interface RazaoCliente {

    /**
     * O corpo dos três pedidos. {@code lancamentoEnvioId}: no recebimento e no estorno, o
     * lançamento do envio de onde o dinheiro sai (o razão confere valor e conta, e aceita uma saída
     * só por envio — ADR-0019); no envio, {@code null}, e o campo nem vai no JSON.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MovimentoPix(UUID contaId, String valor, String descricao, UUID lancamentoEnvioId) {}

    @POST
    @Path("/envios")
    Response enviar(@HeaderParam("Idempotency-Key") String chave, MovimentoPix corpo);

    @POST
    @Path("/recebimentos")
    Response receber(@HeaderParam("Idempotency-Key") String chave, MovimentoPix corpo);

    @POST
    @Path("/estornos")
    Response estornar(@HeaderParam("Idempotency-Key") String chave, MovimentoPix corpo);
}
