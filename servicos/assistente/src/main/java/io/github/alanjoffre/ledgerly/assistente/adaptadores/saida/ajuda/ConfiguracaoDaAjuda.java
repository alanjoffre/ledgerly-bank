package io.github.alanjoffre.ledgerly.assistente.adaptadores.saida.ajuda;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import java.time.Duration;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A base de ajuda: o modelo de vetores no Ollama ({@code bge-m3}, 1024 dimensões) e a tabela no
 * pgvector. Nos testes, um modelo de vetores falso e determinístico entra no lugar.
 */
@Configuration(proxyBeanMethods = false)
class ConfiguracaoDaAjuda {

    private static final Logger LOG = LoggerFactory.getLogger(ConfiguracaoDaAjuda.class);

    @Bean
    @ConditionalOnMissingBean
    EmbeddingModel modeloDeVetores(
            @Value("${assistente.linguagem.url}") String url,
            @Value("${assistente.ajuda.modelo-de-vetores}") String nome,
            @Value("${assistente.linguagem.tempo-limite}") Duration tempoLimite) {
        return OllamaEmbeddingModel.builder().baseUrl(url).modelName(nome).timeout(tempoLimite).maxRetries(0).build();
    }

    @Bean
    PgVectorBaseDeAjuda baseDeAjuda(
            EmbeddingModel vetores,
            DataSource banco,
            @Value("${assistente.ajuda.modelo-de-vetores}") String nome,
            @Value("${assistente.ajuda.dimensao}") int dimensao,
            @Value("${assistente.ajuda.semelhanca-minima}") double semelhancaMinima,
            @Value("${assistente.ajuda.artigos:classpath:ajuda/*.md}") String artigos) {
        EmbeddingStore<TextSegment> loja = PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(banco)
                .table("trechos_de_ajuda")
                .dimension(dimensao)
                .createTable(true)
                .build();
        return new PgVectorBaseDeAjuda(vetores, loja, JdbcClient.create(banco), Artigos.ler(artigos), nome, semelhancaMinima);
    }

    /** Já indexa na subida; se o Ollama não responder, tenta de novo na primeira pergunta. */
    @EventListener(ApplicationReadyEvent.class)
    void indexarNaSubida(ApplicationReadyEvent evento) {
        try {
            evento.getApplicationContext().getBean(PgVectorBaseDeAjuda.class).indexar();
        } catch (RuntimeException e) {
            LOG.warn("Não deu para indexar a ajuda na subida ({}): fica para a primeira pergunta", e.toString());
        }
    }
}
