package io.github.alanjoffre.ledgerly.antifraude.adaptadores.entrada.web;

import io.github.alanjoffre.ledgerly.antifraude.adaptadores.entrada.web.Dtos.AvaliacaoRequisicao;
import io.github.alanjoffre.ledgerly.antifraude.adaptadores.entrada.web.Dtos.AvaliacaoResposta;
import io.github.alanjoffre.ledgerly.antifraude.adaptadores.entrada.web.Dtos.ExplicacaoResposta;
import io.github.alanjoffre.ledgerly.antifraude.aplicacao.AvaliacaoService;
import io.github.alanjoffre.ledgerly.antifraude.aplicacao.ExplicacaoService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;

/**
 * API do antifraude (ADR-0032).
 *
 * <ul>
 *   <li>{@code POST /v1/avaliacoes} — o pix pergunta, antes de debitar: aprovar, segurar ou recusar?
 *       Responde {@code 200} com a nota, a decisão e os motivos. Perguntar de novo pela mesma
 *       transferência devolve a mesma avaliação (a Saga repete quando fica sem resposta).</li>
 *   <li>{@code GET /v1/avaliacoes/{transferenciaId}} — a analista do backoffice vê por que o Pix foi
 *       retido.</li>
 *   <li>{@code GET /v1/avaliacoes/{transferenciaId}/explicacao} — o mesmo porquê num texto curto,
 *       escrito pelo modelo de linguagem local e conferido número a número (ADR-0035). Separado da
 *       avaliação porque, na primeira vez, leva segundos: a tela mostra a nota e os motivos na hora,
 *       e o texto quando chegar.</li>
 * </ul>
 */
@Path("/v1/avaliacoes")
@Produces(MediaType.APPLICATION_JSON)
public class AvaliacaoRecurso {

    private final AvaliacaoService avaliacoes;
    private final ExplicacaoService explicacoes;

    public AvaliacaoRecurso(AvaliacaoService avaliacoes, ExplicacaoService explicacoes) {
        this.avaliacoes = avaliacoes;
        this.explicacoes = explicacoes;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(Papeis.SERVICO)
    public AvaliacaoResposta avaliar(@NotNull @Valid AvaliacaoRequisicao requisicao) {
        return AvaliacaoResposta.de(avaliacoes.avaliar(requisicao.paraPedido()));
    }

    @GET
    @Path("/{transferenciaId}")
    @RolesAllowed({Papeis.ANALISTA, Papeis.SERVICO})
    public AvaliacaoResposta consultar(@PathParam("transferenciaId") UUID transferenciaId) {
        return avaliacoes.consultar(transferenciaId)
                .map(AvaliacaoResposta::de)
                .orElseThrow(() -> new AvaliacaoNaoEncontradaException(transferenciaId));
    }

    @GET
    @Path("/{transferenciaId}/explicacao")
    @RolesAllowed({Papeis.ANALISTA, Papeis.SERVICO})
    public ExplicacaoResposta explicar(@PathParam("transferenciaId") UUID transferenciaId) {
        return explicacoes.explicar(transferenciaId)
                .map(ExplicacaoResposta::de)
                .orElseThrow(() -> new AvaliacaoNaoEncontradaException(transferenciaId));
    }

    /** Nenhuma avaliação para essa transferência. */
    static final class AvaliacaoNaoEncontradaException extends RuntimeException {

        @java.io.Serial
        private static final long serialVersionUID = 1L;

        AvaliacaoNaoEncontradaException(UUID transferenciaId) {
            super("Nenhuma avaliação para a transferência " + transferenciaId);
        }
    }
}
