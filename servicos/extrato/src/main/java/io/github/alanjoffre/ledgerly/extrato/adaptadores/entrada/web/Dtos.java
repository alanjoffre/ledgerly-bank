package io.github.alanjoffre.ledgerly.extrato.adaptadores.entrada.web;

import io.github.alanjoffre.ledgerly.extrato.aplicacao.PaginaDoExtrato;
import io.github.alanjoffre.ledgerly.extrato.dominio.Movimentacao;
import io.github.alanjoffre.ledgerly.extrato.dominio.ParteDoPix;
import io.github.alanjoffre.ledgerly.extrato.dominio.Saldo;
import io.github.alanjoffre.ledgerly.extrato.dominio.SituacaoPix;
import io.github.alanjoffre.ledgerly.extrato.dominio.TipoOperacao;
import io.github.alanjoffre.ledgerly.extrato.dominio.TipoPartida;
import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Os formatos JSON da API. Dinheiro é texto ({@code "10.50"}), como em todo o Ledgerly (ADR-0012).
 */
final class Dtos {

    private Dtos() {}

    /**
     * @param sequencia    a "posição" do saldo no razão: o extrato está atualizado até esta partida
     * @param atualizadoEm quando essa partida foi escriturada no razão
     */
    record SaldoResposta(UUID contaId, String saldo, String moeda, long sequencia, Instant atualizadoEm) {

        static SaldoResposta de(Saldo saldo) {
            return saldo == null ? null : new SaldoResposta(
                    saldo.contaId(),
                    texto(saldo.valor()),
                    saldo.valor().moeda().getCurrencyCode(),
                    saldo.sequencia(),
                    saldo.atualizadoEm());
        }
    }

    record PixResposta(UUID pixId, SituacaoPix situacao, String contraparte, String tipoChave, String chave, String motivo) {

        static PixResposta de(ParteDoPix pix) {
            return pix == null ? null : new PixResposta(
                    pix.pixId(), pix.situacao(), pix.contraparte(), pix.tipoChave(), pix.chave(), pix.motivo());
        }
    }

    /**
     * Uma linha do extrato.
     *
     * @param descricao o título para a tela ("Pix enviado para Maria Souza")
     * @param mensagem  o texto livre do lançamento ("Aluguel"), se houver
     */
    record ItemResposta(
            long sequencia,
            UUID lancamentoId,
            TipoOperacao operacao,
            TipoPartida tipo,
            String valor,
            String saldoApos,
            String moeda,
            String descricao,
            String mensagem,
            Instant registradoEm,
            PixResposta pix) {

        static ItemResposta de(Movimentacao linha) {
            var razao = linha.razao();
            return new ItemResposta(
                    razao.sequencia(),
                    linha.lancamentoId(),
                    razao.operacao(),
                    razao.tipo(),
                    texto(razao.valor()),
                    texto(razao.saldoApos()),
                    razao.valor().moeda().getCurrencyCode(),
                    linha.descricao(),
                    razao.mensagem(),
                    razao.registradoEm(),
                    PixResposta.de(linha.pix()));
        }
    }

    /** @param proximaPagina o {@code antesDe} da próxima página; {@code null} quando acabou */
    record ExtratoResposta(UUID contaId, SaldoResposta saldo, List<ItemResposta> itens, Long proximaPagina) {

        static ExtratoResposta de(PaginaDoExtrato pagina) {
            return new ExtratoResposta(
                    pagina.contaId(),
                    SaldoResposta.de(pagina.saldo()),
                    pagina.itens().stream().map(ItemResposta::de).toList(),
                    pagina.proximaPagina());
        }
    }

    static String texto(Dinheiro dinheiro) {
        return dinheiro.valor().toPlainString();
    }
}
