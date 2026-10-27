package io.github.alanjoffre.ledgerly.extrato.integracao;

import static io.github.alanjoffre.ledgerly.extrato.integracao.MensagensDoContrato.CAIXA;
import static io.github.alanjoffre.ledgerly.extrato.integracao.MensagensDoContrato.LIQUIDACAO_PIX;
import static io.github.alanjoffre.ledgerly.extrato.integracao.MensagensDoContrato.TOPICO_LANCAMENTOS;
import static io.github.alanjoffre.ledgerly.extrato.integracao.MensagensDoContrato.TOPICO_PIX;
import static io.github.alanjoffre.ledgerly.extrato.integracao.MensagensDoContrato.credito;
import static io.github.alanjoffre.ledgerly.extrato.integracao.MensagensDoContrato.debito;
import static io.github.alanjoffre.ledgerly.extrato.integracao.MensagensDoContrato.lancamento;
import static io.github.alanjoffre.ledgerly.extrato.integracao.MensagensDoContrato.pixConcluido;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.alanjoffre.ledgerly.extrato.adaptadores.saida.mongo.ColecoesDoMongo;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.porta.SaldoRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.GroupState;
import org.apache.kafka.common.TopicPartition;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * A promessa do CQRS com um log de eventos: o modelo de leitura é <b>descartável</b>. Apagou o
 * MongoDB inteiro? Relê os tópicos desde o primeiro evento e o extrato volta igual.
 *
 * <p>É o mesmo roteiro do {@code scripts/reconstruir-extrato.sh}: parar o consumo, apagar o banco
 * do extrato, voltar o grupo {@code extrato} para o início dos tópicos e ligar de novo.
 *
 * <p>Os testes também imprimem medições (linhas {@code MEDIDA}): tempo da projeção, da
 * reconstrução e o atraso de cada evento até o extrato.
 */
@DisplayName("Reconstrução do extrato a partir dos eventos")
class ReconstrucaoIT extends TesteDeIntegracao {

    private static final int CONTAS = 20;
    private static final int OPERACOES = 900;
    private static final Instant QUANDO = Instant.parse("2026-10-27T18:30:00.123456Z");

    @Autowired
    private MongoTemplate mongo;

    @Autowired
    private ColecoesDoMongo colecoes;

    @Autowired
    private KafkaListenerEndpointRegistry consumidores;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private SaldoRepository saldos;

    /** O que se espera no extrato depois de todas as mensagens. */
    private record Esperado(List<UUID> contas, int linhas, int linhasDePix, int mensagens) {}

    @Test
    @DisplayName("apagar o MongoDB e reler os tópicos do início devolve exatamente o mesmo extrato")
    void reconstrucao() throws Exception {
        long inicio = System.nanoTime();
        Esperado esperado = publicarHistorico();
        esperarProjecaoCompleta(esperado);
        long projecao = System.nanoTime() - inicio;
        Map<String, List<Document>> antes = fotografia(esperado.contas());

        consumidores.stop();
        long inicioReconstrucao;
        long mensagensRelidas;
        try {
            mongo.dropCollection("movimentacoes");
            mongo.dropCollection("saldos");
            assertThat(mongo.getCollection("movimentacoes").countDocuments()).isZero();
            colecoes.criarIndices();
            inicioReconstrucao = System.nanoTime();
            mensagensRelidas = voltarGrupoParaOInicio();
        } finally {
            consumidores.start();
        }
        esperarProjecaoCompleta(esperado);
        long reconstrucao = System.nanoTime() - inicioReconstrucao;

        assertThat(fotografia(esperado.contas())).isEqualTo(antes);
        System.out.printf("MEDIDA projeção inicial: %d mensagens (%d linhas no extrato) em %d ms%n",
                esperado.mensagens(), esperado.linhas(), TimeUnit.NANOSECONDS.toMillis(projecao));
        System.out.printf("MEDIDA reconstrução: %d mensagens relidas desde o início dos tópicos em %d ms%n",
                mensagensRelidas, TimeUnit.NANOSECONDS.toMillis(reconstrucao));
    }

    /**
     * O atraso de cada evento até o extrato, medido um a um: publicar (com a confirmação do Kafka)
     * e consultar o MongoDB até o saldo novo aparecer.
     */
    @Test
    @DisplayName("atraso de um evento, do Kafka até o extrato (medição)")
    void atrasoDoEvento() {
        UUID conta = UUID.randomUUID();
        int amostras = 200;
        long[] atrasos = new long[amostras];
        for (int i = 1; i <= amostras; i++) {
            long sequencia = 2L * i;
            UUID id = UUID.randomUUID();
            String mensagem = lancamento(id, "DEPOSITO", null, QUANDO,
                    debito(sequencia - 1, CAIXA, "1.00", i + ".00"), credito(sequencia, conta, "1.00", i + ".00"));
            long inicio = System.nanoTime();
            publicarLancamento(id, mensagem);
            await().atMost(ESPERA).pollInterval(Duration.ofMillis(1))
                    .until(() -> saldos.buscar(conta).map(s -> s.sequencia() == sequencia).orElse(false));
            atrasos[i - 1] = System.nanoTime() - inicio;
        }
        Arrays.sort(atrasos);
        System.out.printf("MEDIDA atraso do evento até o extrato (%d amostras): p50 %.1f ms · p95 %.1f ms · máx %.1f ms%n",
                amostras, ms(atrasos[amostras / 2]), ms(atrasos[amostras * 95 / 100]), ms(atrasos[amostras - 1]));
        assertThat(saldo(conta).orElseThrow().sequencia()).isEqualTo(2L * amostras);
    }

    /** 900 operações em 20 contas: depósitos e, de vez em quando, um Pix entre duas delas. */
    private Esperado publicarHistorico() throws Exception {
        List<UUID> contas = IntStream.range(0, CONTAS).mapToObj(i -> UUID.randomUUID()).toList();
        Map<UUID, BigDecimal> saldoDe = new HashMap<>();
        contas.forEach(c -> saldoDe.put(c, BigDecimal.ZERO.setScale(2)));
        BigDecimal liquidacao = BigDecimal.ZERO.setScale(2);
        List<CompletableFuture<?>> envios = new ArrayList<>();
        long sequencia = 1;
        int linhas = 0;
        int linhasDePix = 0;
        int mensagens = 0;
        for (int i = 0; i < OPERACOES; i++) {
            UUID a = contas.get(i % CONTAS);
            UUID b = contas.get((i * 7 + 3) % CONTAS);
            if (i % 9 == 8 && !a.equals(b) && saldoDe.get(a).signum() > 0) {
                BigDecimal valor = new BigDecimal("1.00");
                UUID pix = UUID.randomUUID();
                UUID envio = UUID.randomUUID();
                UUID recebimento = UUID.randomUUID();
                saldoDe.merge(a, valor.negate(), BigDecimal::add);
                liquidacao = liquidacao.add(valor);
                envios.add(kafka.send(TOPICO_LANCAMENTOS, envio.toString(), lancamento(envio, "PIX_ENVIO", "Pix " + i, QUANDO,
                        debito(sequencia++, a, "1.00", saldoDe.get(a).toPlainString()),
                        credito(sequencia++, LIQUIDACAO_PIX, "1.00", liquidacao.toPlainString()))));
                liquidacao = liquidacao.subtract(valor);
                saldoDe.merge(b, valor, BigDecimal::add);
                envios.add(kafka.send(TOPICO_LANCAMENTOS, recebimento.toString(), lancamento(recebimento, "PIX_RECEBIMENTO", "Pix " + i, QUANDO,
                        debito(sequencia++, LIQUIDACAO_PIX, "1.00", liquidacao.toPlainString()),
                        credito(sequencia++, b, "1.00", saldoDe.get(b).toPlainString()))));
                envios.add(kafka.send(TOPICO_PIX, pix.toString(),
                        pixConcluido(pix, a, "Pagador " + a, b, "Recebedor " + b, envio, recebimento)));
                linhas += 2;
                linhasDePix += 2;
                mensagens += 3;
            } else {
                UUID id = UUID.randomUUID();
                saldoDe.merge(a, new BigDecimal("10.00"), BigDecimal::add);
                envios.add(kafka.send(TOPICO_LANCAMENTOS, id.toString(), lancamento(id, "DEPOSITO", null, QUANDO,
                        debito(sequencia++, CAIXA, "10.00", "0.00"),
                        credito(sequencia++, a, "10.00", saldoDe.get(a).toPlainString()))));
                linhas += 1;
                mensagens += 1;
            }
        }
        CompletableFuture.allOf(envios.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
        return new Esperado(contas, linhas, linhasDePix, mensagens);
    }

    private void esperarProjecaoCompleta(Esperado esperado) {
        List<String> contas = esperado.contas().stream().map(UUID::toString).toList();
        await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            var movimentacoes = mongo.getCollection("movimentacoes");
            assertThat(movimentacoes.countDocuments(new Document("contaId", new Document("$in", contas))
                    .append("sequencia", new Document("$exists", true)))).isEqualTo(esperado.linhas());
            assertThat(movimentacoes.countDocuments(new Document("contaId", new Document("$in", contas))
                    .append("pix", new Document("$exists", true)))).isEqualTo(esperado.linhasDePix());
            assertThat(mongo.getCollection("saldos").countDocuments(new Document("_id", new Document("$in", contas))))
                    .isEqualTo(esperado.contas().size());
        });
    }

    /** Todos os documentos das contas do teste, em ordem fixa — para comparar antes e depois. */
    private Map<String, List<Document>> fotografia(List<UUID> contas) {
        List<String> ids = contas.stream().map(UUID::toString).toList();
        List<Document> linhas = new ArrayList<>();
        mongo.getCollection("movimentacoes").find(new Document("contaId", new Document("$in", ids)))
                .sort(new Document("_id", 1)).forEach(linhas::add);
        List<Document> saldosDasContas = new ArrayList<>();
        mongo.getCollection("saldos").find(new Document("_id", new Document("$in", ids)))
                .sort(new Document("_id", 1)).forEach(saldosDasContas::add);
        return Map.of("movimentacoes", linhas, "saldos", saldosDasContas);
    }

    /**
     * O que o {@code kafka-consumer-groups.sh --reset-offsets --to-earliest} faz: aponta o grupo
     * {@code extrato} para o primeiro evento guardado de cada partição.
     *
     * @return quantas mensagens há para reler nos dois tópicos
     */
    private long voltarGrupoParaOInicio() throws Exception {
        try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            // O Kafka só deixa mexer nos offsets de um grupo sem consumidores conectados.
            await().atMost(ESPERA).until(() -> {
                ConsumerGroupDescription grupo = admin.describeConsumerGroups(List.of("extrato"))
                        .describedGroups().get("extrato").get();
                return grupo.groupState() == GroupState.EMPTY;
            });
            Map<TopicPartition, OffsetSpec> pedido = new HashMap<>();
            for (String topico : List.of(TOPICO_LANCAMENTOS, TOPICO_PIX)) {
                for (int particao = 0; particao < 3; particao++) {
                    pedido.put(new TopicPartition(topico, particao), OffsetSpec.earliest());
                }
            }
            var inicio = admin.listOffsets(pedido).all().get();
            var fim = admin.listOffsets(substituir(pedido, OffsetSpec.latest())).all().get();
            Map<TopicPartition, OffsetAndMetadata> novos = new HashMap<>();
            long total = 0;
            for (var entrada : inicio.entrySet()) {
                novos.put(entrada.getKey(), new OffsetAndMetadata(entrada.getValue().offset()));
                total += fim.get(entrada.getKey()).offset() - entrada.getValue().offset();
            }
            admin.alterConsumerGroupOffsets("extrato", novos).all().get();
            return total;
        }
    }

    private static Map<TopicPartition, OffsetSpec> substituir(Map<TopicPartition, OffsetSpec> pedido, OffsetSpec spec) {
        Map<TopicPartition, OffsetSpec> novo = new HashMap<>();
        pedido.keySet().forEach(particao -> novo.put(particao, spec));
        return novo;
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }
}
