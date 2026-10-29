package io.github.alanjoffre.ledgerly.contas.integracao;

import io.github.alanjoffre.ledgerly.contas.aplicacao.porta.Razao;
import io.github.alanjoffre.ledgerly.seguranca.teste.MockMvcComToken;
import io.github.alanjoffre.ledgerly.seguranca.teste.TokensDeTeste;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Spring + PostgreSQL e Kafka de verdade; o razão é o {@link RazaoFalso}.
 *
 * <p>Toda subclasse usa exatamente esta configuração (inclusive o {@code MockMvc}): assim o Spring
 * reaproveita um contexto só — e um par de contêineres só — para todos os testes de integração.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TesteDeIntegracao.Configuracao.class)
public abstract class TesteDeIntegracao {

    protected static final String TOPICO = "contas.eventos.v1";

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuracao {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"));
        }

        @Bean
        @ServiceConnection
        KafkaContainer kafka() {
            return kafkaSemCriacaoAutomatica();
        }

        /**
         * Como no compose, o tópico só existe se for criado de propósito. Aqui quem cria é o
         * {@code KafkaAdmin} do Spring, na subida do teste; em produção, o {@code criar-topicos.sh}.
         */
        @Bean
        NewTopic topicoDeContas() {
            return new NewTopic(TOPICO, 3, (short) 1);
        }

        /** Tokens assinados com a chave da memória, no lugar do Keycloak (ADR-0024). */
        @Bean
        JwtDecoder decodificador() {
            return TokensDeTeste.decodificador();
        }

        /** Os testes da API chamam como serviço; os de dono e de papel mandam o próprio token. */
        @Bean
        MockMvcBuilderCustomizer tokenDeServico() {
            return MockMvcComToken.padrao(TokensDeTeste.servico());
        }

        /** {@code @Primary}: no lugar do adaptador HTTP, os casos de uso recebem o falso. */
        @Bean
        @Primary
        Razao razaoFalso() {
            return new RazaoFalso();
        }
    }

    static KafkaContainer kafkaSemCriacaoAutomatica() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.4.0"))
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    }

    @Autowired
    protected Razao razaoInjetado;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected KafkaContainer kafka;

    protected RazaoFalso razao() {
        return (RazaoFalso) razaoInjetado;
    }

    @BeforeEach
    void limpar() {
        razao().religar();
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM conta");
        jdbc.update("DELETE FROM cliente");
    }

    /** Os tipos dos eventos gravados no Outbox para a conta, na ordem de gravação. */
    protected List<String> eventosNoOutbox(UUID conta) {
        return jdbc.queryForList("SELECT tipo FROM outbox WHERE chave = ? ORDER BY id", String.class, conta.toString());
    }
}
