package io.github.alanjoffre.ledgerly.contas.integracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import io.github.alanjoffre.ledgerly.contas.aplicacao.ClienteService;
import io.github.alanjoffre.ledgerly.contas.aplicacao.ContaService;
import io.github.alanjoffre.ledgerly.contas.aplicacao.porta.RegistroDeEventos;
import io.github.alanjoffre.ledgerly.contas.dominio.Cliente;
import io.github.alanjoffre.ledgerly.contas.dominio.Conta;
import io.github.alanjoffre.ledgerly.contas.dominio.Cpf;
import io.github.alanjoffre.ledgerly.contas.dominio.CpfsDeTeste;
import io.github.alanjoffre.ledgerly.contas.dominio.EventoDeConta.ContaBloqueada;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O Outbox de ponta a ponta (ADR-0013): o evento nasce na transação da mudança e chega ao Kafka
 * no formato do contrato ({@code docs/arquitetura/eventos.md}).
 */
@DisplayName("Outbox: eventos de conta até o Kafka")
class OutboxIT extends TesteDeIntegracao {

    private static final Duration ESPERA = Duration.ofSeconds(30);

    @Autowired
    private ClienteService clientes;

    @Autowired
    private ContaService contas;

    @Autowired
    private RegistroDeEventos registro;

    @Autowired
    private TransactionTemplate transacao;

    private Cliente novoCliente() {
        return clientes.cadastrar("Maria Exemplo", new Cpf(CpfsDeTeste.gerar()));
    }

    @Test
    @DisplayName("a transação falha depois de abrir a conta: nem a conta nem o evento ficam gravados")
    void mesmaTransacao() {
        Cliente cliente = novoCliente();

        assertThatThrownBy(() -> transacao.executeWithoutResult(status -> {
                    contas.abrir(cliente.id());
                    throw new IllegalStateException("falha depois de abrir a conta");
                }))
                .hasMessage("falha depois de abrir a conta");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM conta", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Long.class)).isZero();
    }

    @Test
    @DisplayName("gravar evento fora de uma transação é recusado (seria a escrita dupla que o Outbox evita)")
    void exigeTransacao() {
        assertThatThrownBy(() -> registro.registrar(new ContaBloqueada(UUID.randomUUID(), Instant.now())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("ContaAberta chega ao Kafka: chave = contaId, cabeçalho tipo, envelope do contrato")
    void entregaComEnvelope() {
        try (LeitorDoKafka leitor = new LeitorDoKafka(kafka.getBootstrapServers(), TOPICO)) {
            Cliente cliente = novoCliente();
            Conta conta = contas.abrir(cliente.id());

            List<ConsumerRecord<String, String>> mensagens = leitor.daChave(conta.id().toString(), 1, ESPERA);

            assertThat(mensagens).hasSize(1);
            ConsumerRecord<String, String> mensagem = mensagens.getFirst();
            assertThat(LeitorDoKafka.cabecalho(mensagem, "tipo")).isEqualTo("ContaAberta");
            String json = mensagem.value();
            assertThat(JsonPath.<String>read(json, "$.tipo")).isEqualTo("ContaAberta");
            assertThat(JsonPath.<Integer>read(json, "$.versao")).isEqualTo(1);
            assertThat(Instant.parse(JsonPath.read(json, "$.ocorridoEm")))
                    .isEqualTo(conta.abertaEm().truncatedTo(ChronoUnit.MICROS));
            assertThat(JsonPath.<String>read(json, "$.dados.contaId")).isEqualTo(conta.id().toString());
            assertThat(JsonPath.<String>read(json, "$.dados.clienteId")).isEqualTo(cliente.id().toString());
            assertThat(JsonPath.<String>read(json, "$.dados.moeda")).isEqualTo("BRL");
            assertThat(UUID.fromString(JsonPath.read(json, "$.eventoId")))
                    .isEqualTo(jdbc.queryForObject("SELECT evento_id FROM outbox WHERE chave = ?", UUID.class, conta.id().toString()));

            await().atMost(ESPERA).until(() -> jdbc.queryForObject(
                    "SELECT publicado_em IS NOT NULL FROM outbox WHERE chave = ?", Boolean.class, conta.id().toString()));
        }
    }

    @Test
    @DisplayName("os eventos de uma conta chegam na ordem em que aconteceram (mesma chave, mesma partição)")
    void ordemPorConta() {
        try (LeitorDoKafka leitor = new LeitorDoKafka(kafka.getBootstrapServers(), TOPICO)) {
            Conta conta = contas.abrir(novoCliente().id());
            contas.bloquear(conta.id());
            contas.desbloquear(conta.id());
            contas.encerrar(conta.id());

            List<String> tipos = leitor.daChave(conta.id().toString(), 4, ESPERA).stream()
                    .map(m -> LeitorDoKafka.cabecalho(m, "tipo"))
                    .toList();

            assertThat(tipos).containsExactly(
                    "ContaAberta", "ContaBloqueada", "ContaDesbloqueada", "ContaEncerrada");
        }
    }
}
