package io.github.alanjoffre.ledgerly.pix.adaptadores.entrada.web;

import io.github.alanjoffre.ledgerly.pix.adaptadores.entrada.web.Dtos.TransferenciaRequisicao;
import io.github.alanjoffre.ledgerly.pix.adaptadores.entrada.web.Dtos.TransferenciaResposta;
import io.github.alanjoffre.ledgerly.pix.aplicacao.TransferenciaService;
import io.github.alanjoffre.ledgerly.pix.dominio.TransferenciaInvalidaException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
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
 *
 * <p>Quem pode (ADR-0026): o Pix sai da conta do cliente que pede (ou de um serviço, como o teste
 * de carga); a consulta é do dono, do analista ou de um serviço. A fila de análise e a devolução
 * são do analista do backoffice (ADR-0030).
 */
@Path("/v1/transferencias")
@Produces(MediaType.APPLICATION_JSON)
public class TransferenciaRecurso {

    static final String CABECALHO_CHAVE = "Idempotency-Key";
    static final String CABECALHO_REPETIDO = "Idempotent-Replayed";

    private final TransferenciaService transferencias;
    private final Quem quem;

    public TransferenciaRecurso(TransferenciaService transferencias, Quem quem) {
        this.transferencias = transferencias;
        this.quem = quem;
    }

    @POST
    @RolesAllowed({Quem.CLIENTE, Quem.SERVICO})
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
                requisicao.descricao()),
                quem.acesso());
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
    @RolesAllowed({Quem.CLIENTE, Quem.ANALISTA, Quem.SERVICO})
    public TransferenciaResposta consultar(@PathParam("id") UUID id) {
        return TransferenciaResposta.de(transferencias.consultar(id, quem.acesso()));
    }

    /** A fila do backoffice. Por enquanto, só a situação {@code EM_ANALISE} é listável. */
    @GET
    @RolesAllowed(Quem.ANALISTA)
    public List<TransferenciaResposta> listar(
            @QueryParam("situacao") String situacao,
            @QueryParam("limite") @DefaultValue("50") @Min(1) @Max(200) int limite) {
        if (!"EM_ANALISE".equals(situacao)) {
            throw new FiltroInvalidoException();
        }
        return transferencias.emAnalise(limite).stream().map(TransferenciaResposta::de).toList();
    }

    /** A decisão do analista: devolver o Pix em análise para outra conta do mesmo titular. */
    @POST
    @Path("/{id}/devolucao")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(Quem.ANALISTA)
    public TransferenciaResposta devolver(@PathParam("id") UUID id, @NotNull @Valid Dtos.DevolucaoRequisicao requisicao) {
        return TransferenciaResposta.de(transferencias.devolver(new TransferenciaService.PedidoDeDevolucao(
                id, requisicao.contaDestinoId(), requisicao.justificativa(), quem.id(), quem.nome())));
    }

    /** O filtro {@code situacao} é obrigatório e, hoje, só aceita {@code EM_ANALISE}. */
    static final class FiltroInvalidoException extends TransferenciaInvalidaException {

        @java.io.Serial
        private static final long serialVersionUID = 1L;

        FiltroInvalidoException() {
            super("Informe situacao=EM_ANALISE (a única fila disponível)");
        }

        @Override
        public String codigo() {
            return "requisicao-invalida";
        }
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
