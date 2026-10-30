package io.github.alanjoffre.ledgerly.assistente.adaptadores.saida.linguagem;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import io.github.alanjoffre.ledgerly.assistente.aplicacao.porta.Conversa;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * O modelo de linguagem local, no Ollama (custo zero, ADR-0034). Temperatura baixa: a resposta é
 * para ser fiel ao extrato. Embrulhado para mandar o esquema das ferramentas completo (ver
 * {@link ModeloComEsquemaCompleto}). Nos testes, um modelo falso roteirizado entra no lugar.
 */
@Configuration(proxyBeanMethods = false)
class ConfiguracaoDaLinguagem {

    @Bean
    @ConditionalOnMissingBean
    ChatModel modeloDeLinguagem(
            @Value("${assistente.linguagem.url}") String url,
            @Value("${assistente.linguagem.modelo}") String nome,
            @Value("${assistente.linguagem.tempo-limite}") Duration tempoLimite) {
        return new ModeloComEsquemaCompleto(OllamaChatModel.builder()
                .baseUrl(url)
                .modelName(nome)
                .temperature(0.1)
                .numPredict(300)
                .timeout(tempoLimite)
                .maxRetries(0)
                .build());
    }

    @Bean
    Conversa conversa(ChatModel modelo) {
        return new ConversaComModelo(modelo);
    }
}
