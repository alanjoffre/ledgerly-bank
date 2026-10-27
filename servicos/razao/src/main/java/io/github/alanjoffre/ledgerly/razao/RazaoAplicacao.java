package io.github.alanjoffre.ledgerly.razao;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Ponto de entrada do serviço razão.
 *
 * <p>Pacotes (arquitetura hexagonal):
 * <ul>
 *   <li>{@code dominio} — regras de negócio, Java puro;</li>
 *   <li>{@code aplicacao} — casos de uso e portas (interfaces);</li>
 *   <li>{@code adaptadores.entrada.web} — API REST;</li>
 *   <li>{@code adaptadores.entrada.eventos} — eventos do serviço de contas (Kafka);</li>
 *   <li>{@code adaptadores.saida.persistencia} — PostgreSQL;</li>
 *   <li>{@code adaptadores.saida.eventos} — Outbox e o publicador para o Kafka.</li>
 * </ul>
 *
 * <p>{@code @EnableScheduling} liga as tarefas agendadas ({@code @Scheduled}), como o publicador
 * do Outbox, que roda a cada 100 ms.
 */
@SpringBootApplication
@EnableScheduling
public class RazaoAplicacao {

    public static void main(String[] args) {
        SpringApplication.run(RazaoAplicacao.class, args);
    }

    /** O relógio vira dependência: nos testes dá para fixar a hora. */
    @Bean
    Clock relogio() {
        return Clock.systemUTC();
    }
}
