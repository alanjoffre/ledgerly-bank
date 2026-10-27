package io.github.alanjoffre.ledgerly.pix.adaptadores.saida.razao;

import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Cliente HTTP declarativo do razão: a interface descreve as chamadas e o Quarkus gera o código.
 * Endereço e tempos-limite em {@code quarkus.rest-client.razao.*}.
 *
 * <p>Devolve {@link Response} cru, sem virar exceção nos erros: quem decide o que cada status
 * significa para a Saga é o {@link HttpRazao}.
 */
@RegisterRestClient(configKey = "razao")
@Path("/v1/pix")
public interface RazaoCliente {

    record MovimentoPix(UUID contaId, String valor, String descricao) {}

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
