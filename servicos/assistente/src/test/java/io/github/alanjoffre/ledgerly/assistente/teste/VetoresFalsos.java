package io.github.alanjoffre.ledgerly.assistente.teste;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.request.EmbeddingInput;
import dev.langchain4j.model.embedding.request.EmbeddingRequest;
import dev.langchain4j.model.embedding.response.EmbeddingResponse;
import java.text.Normalizer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * O modelo de vetores dos testes: um "saco de palavras" — cada palavra (sem acento, com 4 letras ou
 * mais) soma 1 numa posição do vetor, pelo hash. Texto com as mesmas palavras dá vetor parecido.
 * Determinístico e sem Ollama; não entende sinônimo, e não precisa: o teste confere o caminho
 * (indexar, buscar, devolver o trecho), não a qualidade da busca — essa é das evals.
 */
public class VetoresFalsos implements EmbeddingModel {

    public static final int DIMENSAO = 128;
    private final AtomicInteger textos = new AtomicInteger();

    @Override
    public EmbeddingResponse doEmbed(EmbeddingRequest pedido) {
        return EmbeddingResponse.builder()
                .embeddings(pedido.inputs().stream().map(EmbeddingInput::text).map(this::vetor).toList())
                .build();
    }

    /** Quantos textos já viraram vetor: prova que indexar de novo não acontece à toa. */
    public int textos() {
        return textos.get();
    }

    private Embedding vetor(String texto) {
        textos.incrementAndGet();
        float[] v = new float[DIMENSAO];
        String simples = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        for (String palavra : simples.split("[^a-z0-9]+")) {
            if (palavra.length() >= 4) {
                v[Math.floorMod(palavra.hashCode(), DIMENSAO)] += 1;
            }
        }
        v[0] += 0.01f;
        Embedding vetor = Embedding.from(v);
        vetor.normalize();
        return vetor;
    }
}
