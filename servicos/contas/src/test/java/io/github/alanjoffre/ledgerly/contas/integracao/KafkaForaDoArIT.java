package io.github.alanjoffre.ledgerly.contas.integracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.alanjoffre.ledgerly.contas.aplicacao.ClienteService;
import io.github.alanjoffre.ledgerly.contas.aplicacao.ContaService;
import io.github.alanjoffre.ledgerly.contas.aplicacao.porta.Razao;
import io.github.alanjoffre.ledgerly.contas.dominio.Conta;
import io.github.alanjoffre.ledgerly.contas.dominio.Cpf;
import io.github.alanjoffre.ledgerly.contas.dominio.CpfsDeTeste;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Kafka fora do ar: a operação na conta continua funcionando, e o evento espera no Outbox até o
 * Kafka voltar. "Fora do ar" aqui é o contêiner <b>pausado</b> ({@code docker pause}): o processo
 * congela, as conexões ficam sem resposta, e ao despausar ele volta no mesmo endereço.
 *
 * <p>Kafka próprio (não o compartilhado pelos outros testes, que não podem ficar sem ele) e
 * tempos-limite curtos no produtor, para o teste não esperar os 10 s do padrão.
 */
@SpringBootTest(properties = {
    "spring.kafka.producer.properties.request.timeout.ms=1000",
    "spring.kafka.producer.properties.delivery.timeout.ms=2000",
    "spring.kafka.producer.properties.max.block.ms=2000",
    "ledgerly.outbox.tempo-limite-envio=3s"
})
@Import(KafkaForaDoArIT.Configuracao.class)
@DisplayName("Outbox com o Kafka fora do ar")
class KafkaForaDoArIT {

    private static final KafkaContainer KAFKA = TesteDeIntegracao.kafkaSemCriacaoAutomatica();

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry propriedades) {
        propriedades.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuracao {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"));
        }

        @Bean
        NewTopic topicoDeContas() {
            return new NewTopic(TesteDeIntegracao.TOPICO, 3, (short) 1);
        }

        @Bean
        @Primary
        Razao razaoFalso() {
            return new RazaoFalso();
        }
    }

    @Autowired
    private ClienteService clientes;

    @Autowired
    private ContaService contas;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("abrir conta com o Kafka parado: responde na hora; o evento sai quando o Kafka volta")
    void eventoEsperaOKafka() {
        try (LeitorDoKafka leitor = new LeitorDoKafka(KAFKA.getBootstrapServers(), TesteDeIntegracao.TOPICO)) {
            pausar();
            Conta conta;
            try {
                long inicio = System.nanoTime();
                conta = contas.abrir(clientes.cadastrar("Maria Exemplo", new Cpf(CpfsDeTeste.gerar())).id());
                Duration demora = Duration.ofNanos(System.nanoTime() - inicio);

                assertThat(demora).as("abrir não espera o Kafka").isLessThan(Duration.ofSeconds(2));
                await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5))
                        .until(() -> pendente(conta));
            } finally {
                despausar();
            }

            assertThat(leitor.daChave(conta.id().toString(), 1, Duration.ofSeconds(60)))
                    .as("o evento chegou depois que o Kafka voltou")
                    .hasSize(1);
            await().atMost(Duration.ofSeconds(30)).until(() -> !pendente(conta));
        }
    }

    private boolean pendente(Conta conta) {
        return jdbc.queryForObject(
                "SELECT publicado_em IS NULL FROM outbox WHERE chave = ?", Boolean.class, conta.id().toString());
    }

    private static void pausar() {
        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
    }

    private static void despausar() {
        KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
    }
}
