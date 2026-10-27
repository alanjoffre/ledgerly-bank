package io.github.alanjoffre.ledgerly.pix.adaptadores.entrada.web;

import io.github.alanjoffre.ledgerly.pix.adaptadores.entrada.web.Dtos.ChaveResposta;
import io.github.alanjoffre.ledgerly.pix.adaptadores.entrada.web.Dtos.ConsultaChaveResposta;
import io.github.alanjoffre.ledgerly.pix.adaptadores.entrada.web.Dtos.QrCodeResposta;
import io.github.alanjoffre.ledgerly.pix.adaptadores.entrada.web.Dtos.RegistrarChaveRequisicao;
import io.github.alanjoffre.ledgerly.pix.aplicacao.ChaveService;
import io.github.alanjoffre.ledgerly.pix.aplicacao.QrCodeService;
import io.github.alanjoffre.ledgerly.pix.dominio.ChaveRegistrada;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * API das chaves Pix e do QR Code.
 *
 * <p><b>Jakarta REST</b> (antigo JAX-RS): o padrão Java para APIs REST, que o Quarkus implementa.
 * {@code @Path} faz o papel do {@code @RequestMapping} do Spring.
 */
@Path("/v1")
@Produces(MediaType.APPLICATION_JSON)
public class ChaveRecurso {

    private final ChaveService chaves;
    private final QrCodeService qrCodes;

    public ChaveRecurso(ChaveService chaves, QrCodeService qrCodes) {
        this.chaves = chaves;
        this.qrCodes = qrCodes;
    }

    @POST
    @Path("/chaves")
    public Response registrar(@NotNull @Valid RegistrarChaveRequisicao requisicao) {
        ChaveRegistrada registrada = chaves.registrar(requisicao.contaId(), requisicao.tipo(), requisicao.valor());
        return Response.created(URI.create("/v1/chaves/" + registrada.chave().valor()))
                .entity(ChaveResposta.de(registrada))
                .build();
    }

    /** "Confira o destinatário": de quem é a chave, sem mostrar o dado inteiro. */
    @GET
    @Path("/chaves/{chave}")
    public ConsultaChaveResposta consultar(@PathParam("chave") String chave) {
        return ConsultaChaveResposta.de(chaves.consultar(chave));
    }

    @DELETE
    @Path("/chaves/{chave}")
    public Response excluir(@PathParam("chave") String chave) {
        chaves.excluir(chave);
        return Response.noContent().build();
    }

    @GET
    @Path("/contas/{contaId}/chaves")
    public List<ChaveResposta> listarDaConta(@PathParam("contaId") UUID contaId) {
        return chaves.listarDaConta(contaId).stream().map(ChaveResposta::de).toList();
    }

    /** O QR Code estático da chave, como texto (o "copia e cola"). Sem {@code valor}, quem paga digita. */
    @GET
    @Path("/chaves/{chave}/qrcode")
    public QrCodeResposta qrCode(
            @PathParam("chave") String chave,
            @QueryParam("valor") @Pattern(regexp = Dtos.PADRAO_VALOR, message = Dtos.MENSAGEM_VALOR) String valor,
            @QueryParam("descricao") @Size(max = 60) String descricao) {
        return QrCodeResposta.de(qrCodes.gerar(chave, Dtos.dinheiro(valor), descricao));
    }

    /** O mesmo QR Code como imagem SVG, pronta para mostrar na tela ou imprimir. */
    @GET
    @Path("/chaves/{chave}/qrcode.svg")
    @Produces("image/svg+xml")
    public String qrCodeSvg(
            @PathParam("chave") String chave,
            @QueryParam("valor") @Pattern(regexp = Dtos.PADRAO_VALOR, message = Dtos.MENSAGEM_VALOR) String valor,
            @QueryParam("descricao") @Size(max = 60) String descricao) {
        return QrCodeSvg.desenhar(qrCodes.gerar(chave, Dtos.dinheiro(valor), descricao).copiaECola());
    }

    /** Lê um "copia e cola" e mostra o que há nele, antes de pagar. Recusa QR Code adulterado. */
    @POST
    @Path("/qrcodes/leitura")
    public QrCodeResposta lerQrCode(@NotNull @Valid Dtos.LerQrCodeRequisicao requisicao) {
        return QrCodeResposta.de(qrCodes.ler(requisicao.copiaECola()));
    }
}
