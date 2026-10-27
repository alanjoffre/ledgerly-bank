package io.github.alanjoffre.ledgerly.extrato.adaptadores.saida.mongo;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.set;
import static io.github.alanjoffre.ledgerly.extrato.adaptadores.saida.mongo.ColecoesDoMongo.decimal;
import static io.github.alanjoffre.ledgerly.extrato.adaptadores.saida.mongo.ColecoesDoMongo.ehChaveDuplicada;
import static io.github.alanjoffre.ledgerly.extrato.adaptadores.saida.mongo.ColecoesDoMongo.moeda;

import com.mongodb.MongoWriteException;
import com.mongodb.client.model.UpdateOptions;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.porta.SaldoRepository;
import io.github.alanjoffre.ledgerly.extrato.dominio.Saldo;
import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import org.bson.Document;
import org.springframework.stereotype.Repository;

/**
 * O saldo de cada conta, coleção {@code saldos}: {@code {"_id": contaId, "saldo", "moeda",
 * "sequencia", "atualizadoEm"}}.
 *
 * <p><b>Upsert condicional</b> — o truque que impede o saldo de voltar no tempo, numa operação só:
 *
 * <pre>{@code
 * updateOne({ _id: conta, sequencia: { $lt: nova } }, { $set: {...} }, { upsert: true })
 * }</pre>
 *
 * <ul>
 *   <li>não existe saldo da conta → nada casa com o filtro → o upsert <b>cria</b>;</li>
 *   <li>existe, com sequência menor → casa → <b>atualiza</b>;</li>
 *   <li>existe, com sequência igual ou maior (evento repetido ou atrasado) → nada casa → o upsert
 *       tenta criar outro documento com o mesmo {@code _id} → o MongoDB recusa com
 *       <b>chave duplicada</b> → "o guardado já é igual ou mais novo": ignorar.</li>
 * </ul>
 *
 * <p><b>A armadilha do primeiro saldo</b> (achada pelo teste de concorrência): quando a conta ainda
 * não tem saldo, dois upserts simultâneos — digamos sequências 39 e 40 — não acham nada e os dois
 * tentam criar. Se o 39 cria primeiro, o 40 leva "chave duplicada", mas o guardado é o MAIS VELHO.
 * Por isso a chave duplicada não é aceita como resposta na primeira vez: tenta-se de novo, uma vez.
 * Agora o documento existe, e o filtro decide de verdade — uma segunda chave duplicada só pode
 * querer dizer "o guardado é igual ou mais novo".
 *
 * <p>Não há "ler, comparar e gravar" separados: com dois consumidores ao mesmo tempo, um evento
 * atrasado sobrescreveria um mais novo entre a leitura e a gravação (prova reversa na ADR-0016).
 */
@Repository
class MongoSaldoRepository implements SaldoRepository {

    private static final UpdateOptions CRIAR_SE_NAO_EXISTIR = new UpdateOptions().upsert(true);

    private final ColecoesDoMongo colecoes;

    MongoSaldoRepository(ColecoesDoMongo colecoes) {
        this.colecoes = colecoes;
    }

    @Override
    public boolean atualizarSeMaisNovo(Saldo saldo) {
        try {
            return gravarSeMaisNovo(saldo);
        } catch (MongoWriteException e) {
            if (!ehChaveDuplicada(e)) {
                throw e;
            }
        }
        // Chave duplicada na primeira tentativa: outro upsert criou o documento agora. Repetir.
        try {
            return gravarSeMaisNovo(saldo);
        } catch (MongoWriteException e) {
            if (ehChaveDuplicada(e)) {
                return false;
            }
            throw e;
        }
    }

    private boolean gravarSeMaisNovo(Saldo saldo) {
        var resultado = colecoes.saldos().updateOne(
                and(eq("_id", saldo.contaId().toString()), lt("sequencia", saldo.sequencia())),
                combine(
                        set("saldo", decimal(saldo.valor().valor())),
                        set("moeda", saldo.valor().moeda().getCurrencyCode()),
                        set("sequencia", saldo.sequencia()),
                        set("atualizadoEm", Date.from(saldo.atualizadoEm()))),
                CRIAR_SE_NAO_EXISTIR);
        return resultado.getModifiedCount() > 0 || resultado.getUpsertedId() != null;
    }

    @Override
    public Optional<Saldo> buscar(UUID contaId) {
        Document documento = colecoes.saldos().find(eq("_id", contaId.toString())).first();
        if (documento == null) {
            return Optional.empty();
        }
        return Optional.of(new Saldo(
                contaId,
                Dinheiro.de(decimal(documento, "saldo"), moeda(documento)),
                documento.getLong("sequencia"),
                documento.getDate("atualizadoEm").toInstant()));
    }
}
