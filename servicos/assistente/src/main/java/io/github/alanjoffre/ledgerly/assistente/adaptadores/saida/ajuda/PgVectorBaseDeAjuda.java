package io.github.alanjoffre.ledgerly.assistente.adaptadores.saida.ajuda;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.github.alanjoffre.ledgerly.assistente.aplicacao.porta.BaseDeAjuda;
import io.github.alanjoffre.ledgerly.assistente.dominio.Trecho;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Adaptador de saída: a busca por significado (RAG) nos artigos de ajuda, no pgvector.
 *
 * <p>Cada trecho vira um vetor (o {@code bge-m3}, multilíngue, pelo Ollama); a pergunta também; o
 * pgvector devolve os trechos de vetor mais próximo (distância de cosseno). Com sete artigos, uma
 * busca por palavra resolveria — o pgvector está aqui pela técnica, e a ADR-0036 diz isso.
 *
 * <p><b>Indexar</b> custa uma chamada ao modelo de vetores por trecho, então só acontece quando os
 * artigos (ou o modelo) mudam: a versão fica na tabela {@code ajuda_indice}. Se o Ollama estiver
 * fora do ar na subida, o serviço sobe assim mesmo e indexa na primeira pergunta.
 */
class PgVectorBaseDeAjuda implements BaseDeAjuda {

    private static final Logger LOG = LoggerFactory.getLogger(PgVectorBaseDeAjuda.class);

    private final EmbeddingModel vetores;
    private final EmbeddingStore<TextSegment> loja;
    private final JdbcClient banco;
    private final List<Trecho> artigos;
    private final String versao;
    private final double semelhancaMinima;
    private volatile boolean indexado;

    PgVectorBaseDeAjuda(EmbeddingModel vetores, EmbeddingStore<TextSegment> loja, JdbcClient banco,
            List<Trecho> artigos, String modeloDeVetores, double semelhancaMinima) {
        this.vetores = vetores;
        this.loja = loja;
        this.banco = banco;
        this.artigos = List.copyOf(artigos);
        this.versao = Artigos.versao(artigos, modeloDeVetores);
        this.semelhancaMinima = semelhancaMinima;
    }

    @Override
    public List<Trecho> buscar(String pergunta, int quantos) {
        indexar();
        Embedding vetor = vetores.embed(pergunta).content();
        return loja.search(EmbeddingSearchRequest.builder()
                        .queryEmbedding(vetor)
                        .maxResults(quantos)
                        .minScore(semelhancaMinima)
                        .build())
                .matches().stream()
                .map(m -> new Trecho(
                        m.embedded().metadata().getString("artigo"),
                        m.embedded().metadata().getString("titulo"),
                        m.embedded().text()))
                .toList();
    }

    /** Indexa os artigos se a versão guardada não for a destes; nada, se já for. */
    synchronized void indexar() {
        if (indexado) {
            return;
        }
        banco.sql("CREATE TABLE IF NOT EXISTS ajuda_indice (versao TEXT PRIMARY KEY, indexado_em TIMESTAMPTZ NOT NULL DEFAULT now())").update();
        boolean atual = banco.sql("SELECT count(*) FROM ajuda_indice WHERE versao = ?").param(versao).query(Long.class).single() > 0;
        if (!atual) {
            List<TextSegment> trechos = artigos.stream()
                    .map(t -> TextSegment.from(t.texto(), Metadata.from("artigo", t.artigo()).put("titulo", t.titulo())))
                    .toList();
            List<Embedding> vetoresDosTrechos = vetores.embedAll(trechos).content();
            loja.removeAll();
            loja.addAll(vetoresDosTrechos, trechos);
            banco.sql("DELETE FROM ajuda_indice").update();
            banco.sql("INSERT INTO ajuda_indice (versao) VALUES (?)").param(versao).update();
            LOG.info("Artigos de ajuda indexados: {} trechos (versão {})", trechos.size(), versao.substring(0, 12));
        }
        indexado = true;
    }

    int trechos() {
        return artigos.size();
    }
}
