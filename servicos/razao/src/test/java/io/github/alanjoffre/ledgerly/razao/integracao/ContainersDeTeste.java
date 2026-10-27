package io.github.alanjoffre.ledgerly.razao.integracao;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Sobe um PostgreSQL e um Kafka de verdade, nas MESMAS versões do Docker Compose, para os testes de
 * integração. {@code @ServiceConnection} faz o Spring apontar o datasource e o Kafka para eles
 * sozinho.
 *
 * <p>O Spring guarda o contexto entre as classes de teste, então os contêineres sobem uma vez só.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ContainersDeTeste {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"));
    }

    /** No Kafka de teste a criação automática de tópicos fica ligada (é o padrão da imagem). */
    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.4.0"));
    }
}
