package io.github.alanjoffre.ledgerly.extrato.integracao;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Sobe um Kafka e um MongoDB de verdade, nas MESMAS versões do Docker Compose, para os testes de
 * integração. {@code @ServiceConnection} faz o Spring apontar para eles sozinho.
 *
 * <p>Os tópicos são criados aqui, como o {@code criar-topicos.sh} faz no ambiente local: 3
 * partições cada, mais as DLTs.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ContainersDeTeste {

    @Bean
    @ServiceConnection
    KafkaContainer conteinerKafka() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.4.0"));
    }

    @Bean
    @ServiceConnection
    MongoDBContainer conteinerMongo() {
        return new MongoDBContainer(DockerImageName.parse("mongo:9.0.4"));
    }

    @Bean
    NewTopic lancamentos() {
        return TopicBuilder.name(MensagensDoContrato.TOPICO_LANCAMENTOS).partitions(3).build();
    }

    @Bean
    NewTopic pix() {
        return TopicBuilder.name(MensagensDoContrato.TOPICO_PIX).partitions(3).build();
    }

    @Bean
    NewTopic lancamentosDlt() {
        return TopicBuilder.name(MensagensDoContrato.TOPICO_LANCAMENTOS + ".dlt").partitions(3).build();
    }

    @Bean
    NewTopic pixDlt() {
        return TopicBuilder.name(MensagensDoContrato.TOPICO_PIX + ".dlt").partitions(3).build();
    }
}
