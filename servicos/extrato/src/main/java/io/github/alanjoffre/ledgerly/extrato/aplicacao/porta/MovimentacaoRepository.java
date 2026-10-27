package io.github.alanjoffre.ledgerly.extrato.aplicacao.porta;

import io.github.alanjoffre.ledgerly.extrato.dominio.Movimentacao;
import io.github.alanjoffre.ledgerly.extrato.dominio.ParteDoPix;
import io.github.alanjoffre.ledgerly.extrato.dominio.ParteDoRazao;
import java.util.List;
import java.util.UUID;

/**
 * Porta de saída: onde moram as linhas do extrato.
 *
 * <p>As duas gravações são <b>idempotentes</b> e <b>comutativas</b>: gravar o mesmo pedaço duas
 * vezes dá o mesmo resultado que uma, e gravar o pedaço do Pix antes ou depois do pedaço do razão
 * dá a mesma linha no fim. É o que deixa o consumidor do Kafka seguro com entrega repetida e
 * fora de ordem.
 */
public interface MovimentacaoRepository {

    /** Grava (ou regrava) o pedaço do razão da linha lançamento + conta. */
    void gravarParteDoRazao(UUID lancamentoId, UUID contaId, ParteDoRazao parte);

    /** Grava (ou regrava) o pedaço do Pix da linha lançamento + conta. */
    void gravarParteDoPix(UUID lancamentoId, UUID contaId, ParteDoPix parte);

    /**
     * Uma página do extrato, da mais nova para a mais antiga. Só linhas que já têm o pedaço do
     * razão.
     *
     * @param antesDe só linhas com sequência menor que esta ({@code null} = do começo)
     * @param limite  quantas linhas no máximo
     */
    List<Movimentacao> pagina(UUID contaId, Long antesDe, int limite);
}
