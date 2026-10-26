package io.github.alanjoffre.ledgerly.contas.integracao;

import io.github.alanjoffre.ledgerly.contas.aplicacao.porta.Razao;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Spring + PostgreSQL de verdade; o razão é o {@link RazaoFalso}. */
@SpringBootTest
@Import(TesteDeIntegracao.Configuracao.class)
abstract class TesteDeIntegracao {

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuracao {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"));
        }

        /** {@code @Primary}: no lugar do adaptador HTTP, os casos de uso recebem o falso. */
        @Bean
        @Primary
        Razao razaoFalso() {
            return new RazaoFalso();
        }
    }

    @Autowired
    protected Razao razaoInjetado;

    @Autowired
    protected JdbcTemplate jdbc;

    protected RazaoFalso razao() {
        return (RazaoFalso) razaoInjetado;
    }

    @BeforeEach
    void limpar() {
        razao().religar();
        jdbc.update("DELETE FROM conta");
        jdbc.update("DELETE FROM cliente");
    }
}
