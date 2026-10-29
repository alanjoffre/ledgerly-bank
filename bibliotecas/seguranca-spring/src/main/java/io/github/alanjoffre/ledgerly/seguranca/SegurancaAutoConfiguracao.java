package io.github.alanjoffre.ledgerly.seguranca;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Configuração automática (o Spring Boot carrega sozinho, pelo arquivo
 * {@code META-INF/spring/...AutoConfiguration.imports}): o decodificador do Keycloak.
 *
 * <p>{@code @ConditionalOnMissingBean}: o teste declara o próprio decodificador (com a chave da
 * memória) e este sai de cena.
 */
@AutoConfiguration
public class SegurancaAutoConfiguracao {

    @Bean
    @ConditionalOnMissingBean
    JwtDecoder decodificadorDoKeycloak(
            @Value("${ledgerly.seguranca.emissor:http://localhost:18081/realms/ledgerly}") String emissor,
            @Value("${ledgerly.seguranca.jwks:}") String jwks,
            @Value("${ledgerly.seguranca.audiencia:ledgerly-api}") String audiencia) {
        String endereco = jwks.isBlank() ? emissor + "/protocol/openid-connect/certs" : jwks;
        return Decodificadores.doKeycloak(endereco, emissor, audiencia);
    }
}
