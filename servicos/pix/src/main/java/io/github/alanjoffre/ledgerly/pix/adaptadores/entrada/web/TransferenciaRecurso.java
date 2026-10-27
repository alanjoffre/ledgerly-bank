package io.github.alanjoffre.ledgerly.pix.adaptadores.entrada.web;

import io.github.alanjoffre.ledgerly.pix.adaptadores.entrada.web.Dtos.TransferenciaRequisicao;
import io.github.alanjoffre.ledgerly.pix.adaptadores.entrada.web.Dtos.TransferenciaResposta;
import io.github.alanjoffre.ledgerly.pix.aplicacao.TransferenciaService;
import io.github.alanjoffre.ledgerly.pix.dominio.TransferenciaInvalidaException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.UUID;

/**
 * API das transferências Pix.
 *
 * <p>{@code POST /v1/transferencias} exige {@code Idempotency-Key}. Respostas:
 * <ul>
 *   <li>{@code 201 Created} — a Saga terminou na hora (concluída, rejeitada ou estornada);</li>
 *   <li>{@code 202 Accepted} — o pedido foi aceito, mas um passo ficou sem resposta do razão; a
 *       Saga continua sozinha, e {@code GET /v1/transferencias/{id}} mostra o andamento;</li>
 *   <li>{@code 200 OK} + {@code Idempotent-Replayed: true} — pedido repetido: a mesma transferência;</li>
 *   <li>{@code 409} — o mesmo pedido ainda está em andamento (segundo clique).</li>
 * </ul>
 */
@Path("/v1/transferencias")
@Produces(MediaType.APPLICATION_JSON)
public class TransferenciaRecurso {

    static final String CABECALHO_CHAVE = "Idempotency-Key";
    static final String CABECALHO_REPETIDO = "Idempotent-Replayed";

    private final TransferenciaService transferencias;

    public TransferenciaRecurso(TransferenciaService transferencias) {
        this.transferencias = transferencias;
    }

    @POST
    public Response transferir(
            @HeaderParam(CABECALHO_CHAVE) String chave, @NotNull @Valid TransferenciaRequisicao requisicao) {
        if (chave == null || chave.isBlank()) {
            throw new CabecalhoObrigatorioException(CABECALHO_CHAVE);
        }
        var resultado = transferencias.iniciar(new TransferenciaService.Pedido(
                chave,
                requisicao.pagadorContaId(),
                requisicao.chave(),
                requisicao.copiaECola(),
                Dtos.dinheiro(requisicao.valor()),
                requisicao.descricao()));
        var corpo = TransferenciaResposta.de(resultado.transferencia());
        if (resultado.repetido()) {
            return Response.ok(corpo).header(CABECALHO_REPETIDO, "true").build();
        }
        Response.ResponseBuilder resposta = corpo.situacao().ehFinal()
                ? Response.created(URI.create("/v1/transferencias/" + corpo.id()))
                : Response.accepted().location(URI.create("/v1/transferencias/" + corpo.id()));
        return resposta.entity(corpo).build();
    }

    @GET
    @Path("/{id}")
    public TransferenciaResposta consultar(@PathParam("id") UUID id) {
        return TransferenciaResposta.de(transferencias.consultar(id));
    }

    /** Um cabeçalho obrigatório não veio. */
    static final class CabecalhoObrigatorioException extends TransferenciaInvalidaException {

        @java.io.Serial
        private static final long serialVersionUID = 1L;

        CabecalhoObrigatorioException(String cabecalho) {
            super("O cabeçalho '%s' é obrigatório".formatted(cabecalho));
        }

        @Override
        public String codigo() {
            return "requisicao-invalida";
        }
    }
}
