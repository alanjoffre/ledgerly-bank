package io.github.alanjoffre.ledgerly.contas;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Ponto de entrada do serviço de contas. Mesma organização hexagonal do razão:
 * {@code dominio}, {@code aplicacao}, {@code adaptadores.entrada.web}, {@code adaptadores.saida.*}.
 */
@SpringBootApplication
public class ContasAplicacao {

    public static void main(String[] args) {
        SpringApplication.run(ContasAplicacao.class, args);
    }

    @Bean
    Clock relogio() {
        return Clock.systemUTC();
    }
}
