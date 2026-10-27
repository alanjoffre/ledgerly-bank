package io.github.alanjoffre.ledgerly.razao.integracao;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Lê um tópico do Kafka do teste do começo, como um consumidor qualquer faria, até achar as
 * mensagens procuradas — para o teste conferir o que de fato saiu no Kafka.
 */
public final class LeitorDeTopico {

    private LeitorDeTopico() {}

    /** Espera (até {@code prazo}) por {@code quantidade} mensagens que atendem ao filtro. */
    public static List<ConsumerRecord<String, String>> esperar(
            String servidores, String topico, Predicate<ConsumerRecord<String, String>> filtro, int quantidade, Duration prazo) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, servidores,
                ConsumerConfig.GROUP_ID_CONFIG, "teste-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> achadas = new ArrayList<>();
        try (var consumidor = new KafkaConsumer<String, String>(config)) {
            consumidor.subscribe(List.of(topico));
            Instant limite = Instant.now().plus(prazo);
            while (achadas.size() < quantidade && Instant.now().isBefore(limite)) {
                for (ConsumerRecord<String, String> registro : consumidor.poll(Duration.ofMillis(200))) {
                    if (filtro.test(registro)) {
                        achadas.add(registro);
                    }
                }
            }
        }
        return achadas;
    }
}
