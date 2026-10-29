package io.github.alanjoffre.ledgerly.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Gateway do Ledgerly Bank: um BFF (<i>Backend for Frontend</i>) por front-end (ADR-0025).
 *
 * <p>O mesmo código sobe em dois perfis, cada um com a sua porta, o seu cookie, o seu cliente no
 * Keycloak e o papel que exige:
 * <ul>
 *   <li>{@code internet-banking} — :8080, cookie {@code LEDGERLY_IB}, papel {@code cliente};</li>
 *   <li>{@code backoffice} — :8090, cookie {@code LEDGERLY_BO}, papel {@code analista}.</li>
 * </ul>
 *
 * <p>O navegador faz login pelo gateway e recebe só um cookie de sessão; o token fica aqui, e vai
 * para os serviços no cabeçalho {@code Authorization} de cada chamada ({@code TokenRelay}).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayAplicacao {

    public static void main(String[] args) {
        SpringApplication.run(GatewayAplicacao.class, args);
    }
}
