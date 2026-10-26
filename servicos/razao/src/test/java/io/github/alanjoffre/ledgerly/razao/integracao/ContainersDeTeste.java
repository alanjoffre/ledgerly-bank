package io.github.alanjoffre.ledgerly.razao.integracao;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Sobe um PostgreSQL de verdade, na MESMA versão do Docker Compose, para os testes de integração.
 * {@code @ServiceConnection} faz o Spring apontar o datasource para ele sozinho.
 *
 * <p>O Spring guarda o contexto entre as classes de teste, então o contêiner sobe uma vez só.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ContainersDeTeste {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"));
    }
}
