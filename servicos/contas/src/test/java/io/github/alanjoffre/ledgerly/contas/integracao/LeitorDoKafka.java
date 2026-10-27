package io.github.alanjoffre.ledgerly.contas.integracao;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Lê um tópico do começo, como um consumidor de verdade faria (o razão, por exemplo), para o teste
 * conferir o que o publicador do Outbox entregou.
 */
final class LeitorDoKafka implements AutoCloseable {

    private final KafkaConsumer<String, String> consumidor;

    LeitorDoKafka(String servidores, String topico) {
        consumidor = new KafkaConsumer<>(Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, servidores,
                // Grupo novo a cada teste + "earliest": lê o tópico desde a primeira mensagem.
                ConsumerConfig.GROUP_ID_CONFIG, "teste-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumidor.subscribe(List.of(topico));
    }

    /** Espera até chegarem {@code quantidade} mensagens com esta chave (ou o tempo acabar). */
    List<ConsumerRecord<String, String>> daChave(String chave, int quantidade, Duration limite) {
        List<ConsumerRecord<String, String>> recebidas = new ArrayList<>();
        Instant prazo = Instant.now().plus(limite);
        while (recebidas.size() < quantidade && Instant.now().isBefore(prazo)) {
            for (ConsumerRecord<String, String> mensagem : consumidor.poll(Duration.ofMillis(200))) {
                if (chave.equals(mensagem.key())) {
                    recebidas.add(mensagem);
                }
            }
        }
        return recebidas;
    }

    static String cabecalho(ConsumerRecord<String, String> mensagem, String nome) {
        Header cabecalho = mensagem.headers().lastHeader(nome);
        return cabecalho == null ? null : new String(cabecalho.value(), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        consumidor.close();
    }
}
