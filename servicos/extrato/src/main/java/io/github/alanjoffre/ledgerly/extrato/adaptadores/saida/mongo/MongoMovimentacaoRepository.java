package io.github.alanjoffre.ledgerly.extrato.adaptadores.saida.mongo;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Sorts.descending;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.set;
import static io.github.alanjoffre.ledgerly.extrato.adaptadores.saida.mongo.ColecoesDoMongo.decimal;
import static io.github.alanjoffre.ledgerly.extrato.adaptadores.saida.mongo.ColecoesDoMongo.ehChaveDuplicada;
import static io.github.alanjoffre.ledgerly.extrato.adaptadores.saida.mongo.ColecoesDoMongo.moeda;

import com.mongodb.MongoWriteException;
import com.mongodb.client.model.UpdateOptions;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.porta.MovimentacaoRepository;
import io.github.alanjoffre.ledgerly.extrato.dominio.Movimentacao;
import io.github.alanjoffre.ledgerly.extrato.dominio.PapelNoPix;
import io.github.alanjoffre.ledgerly.extrato.dominio.ParteDoPix;
import io.github.alanjoffre.ledgerly.extrato.dominio.ParteDoRazao;
import io.github.alanjoffre.ledgerly.extrato.dominio.SituacaoPix;
import io.github.alanjoffre.ledgerly.extrato.dominio.TipoOperacao;
import io.github.alanjoffre.ledgerly.extrato.dominio.TipoPartida;
import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.stereotype.Repository;

/**
 * As linhas do extrato no MongoDB, coleção {@code movimentacoes}. Um documento por lançamento +
 * conta:
 *
 * <pre>{@code
 * { "_id": "<lancamentoId>:<contaId>",
 *   "lancamentoId": "...", "contaId": "...",
 *   "sequencia": 1041,                      // só existe depois que o pedaço do razão chegou
 *   "razao": { "operacao": "PIX_ENVIO", "tipo": "DEBITO", "valor": 1200.00, "saldoApos": 300.00,
 *              "moeda": "BRL", "mensagem": "Aluguel", "registradoEm": ISODate(...) },
 *   "pix":   { "pixId": "...", "papel": "ENVIADO", "situacao": "CONCLUIDO",
 *              "contraparte": "Maria Souza", "tipoChave": "EMAIL", "chave": "m***@exemplo.com" } }
 * }</pre>
 *
 * <p><b>Upsert com {@code $set}</b>: "atualize este documento; se não existir, crie". Cada tópico
 * escreve só os próprios campos ({@code razao} ou {@code pix}), então os dois pedaços se juntam no
 * mesmo documento em qualquer ordem, e regravar um pedaço igual não muda nada (idempotente).
 */
@Repository
class MongoMovimentacaoRepository implements MovimentacaoRepository {

    private static final UpdateOptions CRIAR_SE_NAO_EXISTIR = new UpdateOptions().upsert(true);

    private final ColecoesDoMongo colecoes;

    MongoMovimentacaoRepository(ColecoesDoMongo colecoes) {
        this.colecoes = colecoes;
    }

    @Override
    public void gravarParteDoRazao(UUID lancamentoId, UUID contaId, ParteDoRazao parte) {
        var razao = new Document()
                .append("operacao", parte.operacao().name())
                .append("tipo", parte.tipo().name())
                .append("valor", decimal(parte.valor().valor()))
                .append("saldoApos", decimal(parte.saldoApos().valor()))
                .append("moeda", parte.valor().moeda().getCurrencyCode())
                .append("mensagem", parte.mensagem())
                .append("registradoEm", Date.from(parte.registradoEm()));
        gravar(lancamentoId, contaId, combine(set("sequencia", parte.sequencia()), set("razao", razao)));
    }

    @Override
    public void gravarParteDoPix(UUID lancamentoId, UUID contaId, ParteDoPix parte) {
        var pix = new Document()
                .append("pixId", parte.pixId().toString())
                .append("papel", parte.papel().name())
                .append("situacao", parte.situacao().name())
                .append("contraparte", parte.contraparte())
                .append("tipoChave", parte.tipoChave())
                .append("chave", parte.chave())
                .append("motivo", parte.motivo());
        gravar(lancamentoId, contaId, set("pix", pix));
    }

    private void gravar(UUID lancamentoId, UUID contaId, Bson pedaco) {
        Bson filtro = eq("_id", lancamentoId + ":" + contaId);
        Bson atualizacao = combine(set("lancamentoId", lancamentoId.toString()), set("contaId", contaId.toString()), pedaco);
        try {
            colecoes.movimentacoes().updateOne(filtro, atualizacao, CRIAR_SE_NAO_EXISTIR);
        } catch (MongoWriteException e) {
            // Os dois consumidores (razão e Pix) criaram o mesmo documento no mesmo instante: um
            // inseriu, o outro bateu na chave. Agora o documento existe, e a segunda tentativa
            // vira uma atualização comum.
            if (!ehChaveDuplicada(e)) {
                throw e;
            }
            colecoes.movimentacoes().updateOne(filtro, atualizacao, CRIAR_SE_NAO_EXISTIR);
        }
    }

    @Override
    public List<Movimentacao> pagina(UUID contaId, Long antesDe, int limite) {
        // "sequencia < X" também descarta os documentos sem sequência (só com o pedaço do Pix):
        // linha sem o pedaço do razão não aparece.
        Bson filtro = and(eq("contaId", contaId.toString()), lt("sequencia", antesDe == null ? Long.MAX_VALUE : antesDe));
        List<Movimentacao> linhas = new ArrayList<>(limite);
        colecoes.movimentacoes().find(filtro).sort(descending("sequencia")).limit(limite)
                .forEach(documento -> linhas.add(paraMovimentacao(documento)));
        return linhas;
    }

    private static Movimentacao paraMovimentacao(Document documento) {
        Document razao = documento.get("razao", Document.class);
        var moeda = moeda(razao);
        var parteDoRazao = new ParteDoRazao(
                documento.getLong("sequencia"),
                TipoOperacao.valueOf(razao.getString("operacao")),
                TipoPartida.valueOf(razao.getString("tipo")),
                Dinheiro.de(decimal(razao, "valor"), moeda),
                Dinheiro.de(decimal(razao, "saldoApos"), moeda),
                razao.getString("mensagem"),
                razao.getDate("registradoEm").toInstant());
        Document pix = documento.get("pix", Document.class);
        ParteDoPix parteDoPix = pix == null ? null : new ParteDoPix(
                UUID.fromString(pix.getString("pixId")),
                PapelNoPix.valueOf(pix.getString("papel")),
                SituacaoPix.valueOf(pix.getString("situacao")),
                pix.getString("contraparte"),
                pix.getString("tipoChave"),
                pix.getString("chave"),
                pix.getString("motivo"));
        return new Movimentacao(
                UUID.fromString(documento.getString("lancamentoId")),
                UUID.fromString(documento.getString("contaId")),
                parteDoRazao,
                parteDoPix);
    }
}
