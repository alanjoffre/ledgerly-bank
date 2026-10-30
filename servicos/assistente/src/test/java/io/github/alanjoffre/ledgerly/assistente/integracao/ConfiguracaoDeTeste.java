package io.github.alanjoffre.ledgerly.assistente.integracao;

import io.github.alanjoffre.ledgerly.assistente.teste.ModeloRoteirizado;
import io.github.alanjoffre.ledgerly.assistente.teste.VetoresFalsos;
import io.github.alanjoffre.ledgerly.seguranca.teste.TokensDeTeste;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * O pgvector de verdade (a mesma imagem do Docker Compose), os tokens assinados na memória, e no
 * lugar do Ollama o modelo roteirizado e o modelo de vetores falso.
 */
@TestConfiguration(proxyBeanMethods = false)
class ConfiguracaoDeTeste {

    @Bean
    @ServiceConnection
    PostgreSQLContainer conteinerPgvector() {
        return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.7-pg18").asCompatibleSubstituteFor("postgres"));
    }

    @Bean
    JwtDecoder decodificador() {
        return TokensDeTeste.decodificador();
    }

    @Bean
    @Primary
    ModeloRoteirizado modeloRoteirizado() {
        return new ModeloRoteirizado();
    }

    @Bean
    @Primary
    VetoresFalsos vetoresFalsos() {
        return new VetoresFalsos();
    }
}
