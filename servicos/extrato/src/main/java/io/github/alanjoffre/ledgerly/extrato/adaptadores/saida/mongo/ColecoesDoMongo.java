package io.github.alanjoffre.ledgerly.extrato.adaptadores.saida.mongo;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import java.math.BigDecimal;
import java.util.Currency;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * As coleções do modelo de leitura e os índices delas.
 *
 * <p>No MongoDB, "coleção" é o equivalente da tabela, e "documento" (um JSON) o da linha. Não há
 * esquema fixo nem migração: os índices são criados quando o serviço sobe — e criar um índice que
 * já existe não faz nada, então subir de novo é seguro.
 *
 * <p><b>Dinheiro como {@code Decimal128}</b>: o tipo decimal exato do MongoDB (128 bits, 34 dígitos),
 * o equivalente do {@code NUMERIC} do PostgreSQL. Nunca {@code double}, pelo mesmo motivo da
 * ADR-0007: 0.1 + 0.2 não pode dar 0.30000000000000004 num extrato.
 */
@Component
public class ColecoesDoMongo implements InitializingBean {

    static final String MOVIMENTACOES = "movimentacoes";
    static final String SALDOS = "saldos";

    private final MongoTemplate mongo;

    public ColecoesDoMongo(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** Roda quando o serviço sobe, antes de os consumidores do Kafka começarem. */
    @Override
    public void afterPropertiesSet() {
        criarIndices();
    }

    /**
     * O extrato de uma conta é lido sempre do mesmo jeito: as linhas da conta, da maior sequência
     * para a menor. Este índice composto entrega exatamente isso, já ordenado, sem varrer a coleção.
     */
    public void criarIndices() {
        movimentacoes().createIndex(
                Indexes.compoundIndex(Indexes.ascending("contaId"), Indexes.descending("sequencia")),
                new IndexOptions().name("extrato_por_conta"));
    }

    MongoCollection<Document> movimentacoes() {
        return mongo.getCollection(MOVIMENTACOES);
    }

    MongoCollection<Document> saldos() {
        return mongo.getCollection(SALDOS);
    }

    static Decimal128 decimal(BigDecimal valor) {
        return new Decimal128(valor);
    }

    static BigDecimal decimal(Document documento, String campo) {
        return documento.get(campo, Decimal128.class).bigDecimalValue();
    }

    static Currency moeda(Document documento) {
        return Currency.getInstance(documento.getString("moeda"));
    }

    /** O MongoDB recusou porque o {@code _id} já existe (código 11000). */
    static boolean ehChaveDuplicada(MongoWriteException e) {
        return e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY;
    }
}
