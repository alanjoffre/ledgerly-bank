package io.github.alanjoffre.ledgerly.extrato.aplicacao;

import io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos.LancamentoRegistrado;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos.PartidaRegistrada;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos.PixConcluido;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos.PixEstornado;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.porta.MovimentacaoRepository;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.porta.SaldoRepository;
import io.github.alanjoffre.ledgerly.extrato.dominio.PapelNoPix;
import io.github.alanjoffre.ledgerly.extrato.dominio.ParteDoPix;
import io.github.alanjoffre.ledgerly.extrato.dominio.ParteDoRazao;
import io.github.alanjoffre.ledgerly.extrato.dominio.Saldo;
import io.github.alanjoffre.ledgerly.extrato.dominio.SituacaoPix;
import org.springframework.stereotype.Service;

/**
 * A <b>projeção</b> do CQRS: transforma cada evento em gravações no modelo de leitura.
 *
 * <p>Não há transação aqui, e não precisa: cada gravação é idempotente e independe da ordem (ver
 * {@link MovimentacaoRepository}). Se o serviço cair no meio de um evento, o Kafka entrega o evento
 * de novo, e regravar o que já estava gravado não muda nada.
 *
 * <p>Na ordem de cada lançamento, a linha do extrato é gravada <b>antes</b> do saldo: quem lê nunca
 * vê um saldo que ainda não tem a linha que o explica.
 */
@Service
public class ProjecaoDoExtrato {

    private final MovimentacaoRepository movimentacoes;
    private final SaldoRepository saldos;

    public ProjecaoDoExtrato(MovimentacaoRepository movimentacoes, SaldoRepository saldos) {
        this.movimentacoes = movimentacoes;
        this.saldos = saldos;
    }

    /** Uma linha para cada partida de conta de cliente; as contas internas do banco ficam de fora. */
    public void projetar(LancamentoRegistrado evento) {
        for (PartidaRegistrada partida : evento.partidas()) {
            if (partida.contaInterna()) {
                continue;
            }
            var parte = new ParteDoRazao(
                    partida.sequencia(),
                    evento.tipo(),
                    partida.tipo(),
                    partida.valor(),
                    partida.saldoApos(),
                    evento.descricao(),
                    evento.registradoEm());
            movimentacoes.gravarParteDoRazao(evento.lancamentoId(), partida.contaId(), parte);
            saldos.atualizarSeMaisNovo(Saldo.depoisDe(partida.contaId(), parte));
        }
    }

    /** Pix concluído: o nome do recebedor na linha do pagador, e o do pagador na linha do recebedor. */
    public void projetar(PixConcluido evento) {
        var recebedor = evento.recebedor();
        movimentacoes.gravarParteDoPix(evento.lancamentoEnvioId(), evento.pagador().contaId(), new ParteDoPix(
                evento.pixId(), PapelNoPix.ENVIADO, SituacaoPix.CONCLUIDO,
                recebedor.nome(), recebedor.tipoChave(), recebedor.chave(), null));
        movimentacoes.gravarParteDoPix(evento.lancamentoRecebimentoId(), recebedor.contaId(), new ParteDoPix(
                evento.pixId(), PapelNoPix.RECEBIDO, SituacaoPix.CONCLUIDO,
                evento.pagador().nome(), null, null, null));
    }

    /**
     * Pix estornado: as duas linhas são do pagador — a saída, marcada como estornada, e a volta do
     * dinheiro, com o motivo. O recebedor não vê nada: para ele o dinheiro nunca chegou.
     *
     * <p>Na devolução de um Pix em análise (ADR-0030), a volta cai em outra conta do mesmo titular
     * (a que pagou foi encerrada): a segunda linha vai para ela.
     */
    public void projetar(PixEstornado evento) {
        var recebedor = evento.recebedor();
        var pagador = evento.pagador().contaId();
        movimentacoes.gravarParteDoPix(evento.lancamentoEnvioId(), pagador, new ParteDoPix(
                evento.pixId(), PapelNoPix.ENVIADO, SituacaoPix.ESTORNADO,
                recebedor.nome(), recebedor.tipoChave(), recebedor.chave(), evento.motivo()));
        movimentacoes.gravarParteDoPix(evento.lancamentoEstornoId(), evento.contaDaVolta(), new ParteDoPix(
                evento.pixId(), PapelNoPix.ESTORNO, SituacaoPix.ESTORNADO,
                recebedor.nome(), recebedor.tipoChave(), recebedor.chave(), evento.motivo()));
    }
}
