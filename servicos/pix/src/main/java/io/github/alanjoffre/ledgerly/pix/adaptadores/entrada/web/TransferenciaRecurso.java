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
import io.github.alanjoffre.ledgerly.pix.dominio.TransferenciaPix;
import java.util.List;
import java.util.UUID;

/**
 * API das transferências Pix.
 *
 * <p>{@code POST /v1/transferencias} exige {@code Idempotency-Key}. Respostas:
 * <ul>
 *   <li>{@code 201 Created} — a Saga terminou na hora (concluída, rejeitada ou estornada);</li>
 *   <li>{@code 202 Accepted} — o pedido foi aceito, mas um passo ficou sem resposta do razão (a
 *       Saga continua sozinha) ou o antifraude reteve o Pix (a analista decide);
 *       {@code GET /v1/transferencias/{id}} mostra o andamento;</li>
 *   <li>{@code 200 OK} + {@code Idempotent-Replayed: true} — pedido repetido: a mesma transferência;</li>
 *   <li>{@code 409} — o mesmo pedido ainda está em andamento (segundo clique).</li>
 * </ul>
 *
 * <p>Quem pode (ADR-0026): o Pix sai da conta do cliente que pede (ou de um serviço, como o teste
 * de carga); a consulta é do dono, do analista ou de um serviço. As filas (em análise e retidos), a
 * devolução, a liberação e a recusa são do analista do backoffice (ADR-0030, ADR-0033).
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
        var corpo = resposta(resultado.transferencia());
        if (resultado.repetido()) {
            return Response.ok(corpo).header(CABECALHO_REPETIDO, "true").build();
        }
        Response.ResponseBuilder resposta = corpo.situacao().respostaDefinitiva()
                ? Response.created(URI.create("/v1/transferencias/" + corpo.id()))
                : Response.accepted().location(URI.create("/v1/transferencias/" + corpo.id()));
        return resposta.entity(corpo).build();
    }

    @GET
    @Path("/{id}")
    @RolesAllowed({Quem.CLIENTE, Quem.ANALISTA, Quem.SERVICO})
    public TransferenciaResposta consultar(@PathParam("id") UUID id) {
        return resposta(transferencias.consultar(id, quem.acesso()));
    }

    /** O cliente não vê a nota nem os motivos do antifraude; analista e serviço veem tudo. */
    private TransferenciaResposta resposta(TransferenciaPix t) {
        return quem.veAAnaliseDeRisco() ? TransferenciaResposta.de(t) : TransferenciaResposta.paraOCliente(t);
    }

    /** As filas do backoffice: {@code EM_ANALISE} (estorno recusado) e {@code RETIDA} (o antifraude segurou). */
    @GET
    @RolesAllowed(Quem.ANALISTA)
    public List<TransferenciaResposta> listar(
            @QueryParam("situacao") String situacao,
            @QueryParam("limite") @DefaultValue("50") @Min(1) @Max(200) int limite) {
        List<TransferenciaPix> fila = switch (situacao == null ? "" : situacao) {
            case "EM_ANALISE" -> transferencias.emAnalise(limite);
            case "RETIDA" -> transferencias.retidas(limite);
            default -> throw new FiltroInvalidoException();
        };
        return fila.stream().map(TransferenciaResposta::de).toList();
    }

    /** A analista liberou o Pix retido: a Saga segue na hora (débito e crédito). */
    @POST
    @Path("/{id}/liberacao")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(Quem.ANALISTA)
    public TransferenciaResposta liberar(@PathParam("id") UUID id, @NotNull @Valid Dtos.DecisaoRequisicao requisicao) {
        return decidir(id, true, requisicao);
    }

    /** A analista recusou o Pix retido: fim, sem dinheiro movido. */
    @POST
    @Path("/{id}/recusa")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(Quem.ANALISTA)
    public TransferenciaResposta recusar(@PathParam("id") UUID id, @NotNull @Valid Dtos.DecisaoRequisicao requisicao) {
        return decidir(id, false, requisicao);
    }

    private TransferenciaResposta decidir(UUID id, boolean liberar, Dtos.DecisaoRequisicao requisicao) {
        return TransferenciaResposta.de(transferencias.decidirRetida(new TransferenciaService.PedidoDeDecisao(
                id, liberar, requisicao.justificativa(), quem.id(), quem.nome())));
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

    /** O filtro {@code situacao} é obrigatório e só aceita as filas do backoffice. */
    static final class FiltroInvalidoException extends TransferenciaInvalidaException {

        @java.io.Serial
        private static final long serialVersionUID = 1L;

        FiltroInvalidoException() {
            super("Informe situacao=EM_ANALISE ou situacao=RETIDA (as filas do backoffice)");
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
