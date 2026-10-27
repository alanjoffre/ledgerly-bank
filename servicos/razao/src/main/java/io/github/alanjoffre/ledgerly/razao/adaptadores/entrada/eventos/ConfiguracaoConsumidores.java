package io.github.alanjoffre.ledgerly.razao.adaptadores.entrada.eventos;

import io.github.alanjoffre.ledgerly.razao.dominio.RegraDeNegocioException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * O que fazer quando um consumidor de eventos falha.
 *
 * <ul>
 *   <li><b>Falha passageira</b> (banco ocupado, rede): tenta de novo, algumas vezes, com pausa;</li>
 *   <li><b>Falha que não passa</b> (JSON inválido, regra de negócio recusada): tentar de novo dá o
 *       mesmo erro, então vai direto;</li>
 *   <li>nos dois casos, se não deu, a mensagem vai para o <b>DLT</b> ({@code <tópico>.dlt},
 *       <i>dead letter topic</i>) — guardada para análise, e a fila segue. Sem isso, uma mensagem
 *       ruim travaria todas as que vêm atrás dela (a "mensagem venenosa", <i>poison pill</i>).</li>
 * </ul>
 */
@Configuration
class ConfiguracaoConsumidores {

    @Bean
    CommonErrorHandler tratadorDeFalhas(
            KafkaTemplate<String, String> kafka,
            @Value("${razao.kafka.tentativas:3}") long tentativas,
            @Value("${razao.kafka.pausa-entre-tentativas-ms:500}") long pausaMs) {
        // Mesmo número de partição no DLT: a ordem relativa das mensagens com problema se mantém.
        var paraDlt = new DeadLetterPublishingRecoverer(
                kafka, (registro, erro) -> new TopicPartition(registro.topic() + ".dlt", registro.partition()));
        var tratador = new DefaultErrorHandler(paraDlt, new FixedBackOff(pausaMs, tentativas));
        tratador.addNotRetryableExceptions(EventoInvalidoException.class, RegraDeNegocioException.class);
        return tratador;
    }
}
