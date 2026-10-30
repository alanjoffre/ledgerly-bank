package io.github.alanjoffre.ledgerly.antifraude.adaptadores.entrada.web;

import io.github.alanjoffre.ledgerly.antifraude.dominio.Avaliacao;
import io.github.alanjoffre.ledgerly.antifraude.dominio.Decisao;
import io.github.alanjoffre.ledgerly.antifraude.dominio.Explicacao;
import io.github.alanjoffre.ledgerly.antifraude.dominio.FatosDoPix;
import io.github.alanjoffre.ledgerly.antifraude.dominio.Motivo;
import io.github.alanjoffre.ledgerly.antifraude.dominio.PedidoDeAvaliacao;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Os formatos JSON da API do antifraude. Dinheiro é texto ({@code "10.50"}), como nos outros serviços (ADR-0012). */
final class Dtos {

    private Dtos() {}

    static final String PADRAO_VALOR = "^\\d{1,17}(\\.\\d{1,2})?$";
    static final String MENSAGEM_VALOR = "deve ser um valor como \"10.50\" (ponto decimal, até 2 casas)";

    // ---- Entrada ----

    /** O Pix que o serviço pix quer avaliar, antes de debitar o pagador. */
    record AvaliacaoRequisicao(
            @NotNull UUID transferenciaId,
            @NotNull UUID pagadorContaId,
            @NotNull UUID recebedorContaId,
            @NotNull @Pattern(regexp = PADRAO_VALOR, message = MENSAGEM_VALOR) String valor,
            @NotNull Instant instante,
            @NotNull Instant contaRecebedoraAbertaEm) {

        PedidoDeAvaliacao paraPedido() {
            return new PedidoDeAvaliacao(
                    transferenciaId, pagadorContaId, recebedorContaId, new BigDecimal(valor), instante,
                    contaRecebedoraAbertaEm);
        }
    }

    // ---- Saída ----

    /** Os fatos como o modelo os leu: a analista vê os mesmos números que decidiram. */
    record FatosResposta(
            String valor,
            String razaoDaMedia,
            int pixNaUltimaHora,
            boolean recebedorNovo,
            boolean madrugada,
            long idadeDaContaRecebedoraEmDias) {

        static FatosResposta de(FatosDoPix f) {
            return new FatosResposta(
                    f.valor().toPlainString(),
                    f.razaoDaMedia() == null ? null : f.razaoDaMedia().stripTrailingZeros().toPlainString(),
                    f.pixNaUltimaHora(),
                    f.recebedorNovo(),
                    f.madrugada(),
                    f.idadeDaContaRecebedoraEmDias());
        }
    }

    record AvaliacaoResposta(
            UUID transferenciaId,
            int nota,
            Decisao decisao,
            List<Motivo> motivos,
            FatosResposta fatos,
            String modelo,
            Instant avaliadaEm) {

        static AvaliacaoResposta de(Avaliacao a) {
            return new AvaliacaoResposta(
                    a.pedido().transferenciaId(),
                    a.nota(),
                    a.decisao(),
                    a.motivos(),
                    FatosResposta.de(a.fatos()),
                    a.modelo(),
                    a.avaliadaEm());
        }
    }

    /** @param modelo o modelo de linguagem que escreveu; {@code null} no texto fixo */
    record ExplicacaoResposta(String texto, Explicacao.Origem origem, String modelo, Instant explicadaEm) {

        static ExplicacaoResposta de(Explicacao e) {
            return new ExplicacaoResposta(e.texto(), e.origem(), e.modelo(), e.explicadaEm());
        }
    }
}
