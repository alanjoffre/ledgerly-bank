package io.github.alanjoffre.ledgerly.assistente;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Ponto de entrada do serviço assistente ("pergunte ao seu extrato", ADR-0036).
 *
 * <p>Pacotes (arquitetura hexagonal):
 * <ul>
 *   <li>{@code dominio} — as travas de entrada e de saída, e a resposta, Java puro;</li>
 *   <li>{@code aplicacao} — o caso de uso e as ferramentas do cliente, e as portas;</li>
 *   <li>{@code adaptadores.entrada.web} — a API REST;</li>
 *   <li>{@code adaptadores.saida.bancario} — o contas e o extrato, com o token da pessoa;</li>
 *   <li>{@code adaptadores.saida.linguagem} — o modelo de linguagem no Ollama, pelo LangChain4j;</li>
 *   <li>{@code adaptadores.saida.ajuda} — os artigos de ajuda no pgvector (RAG).</li>
 * </ul>
 */
@SpringBootApplication
public class AssistenteAplicacao {

    public static void main(String[] args) {
        SpringApplication.run(AssistenteAplicacao.class, args);
    }

    @Bean
    Clock relogio() {
        return Clock.systemUTC();
    }
}
