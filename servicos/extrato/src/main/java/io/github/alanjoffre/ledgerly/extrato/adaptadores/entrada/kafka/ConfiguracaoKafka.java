package io.github.alanjoffre.ledgerly.extrato.adaptadores.entrada.kafka;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * O que fazer quando um evento falha. Dois tipos de falha, dois tratamentos:
 *
 * <table>
 *   <caption>Falhas do consumidor</caption>
 *   <tr><th>Falha</th><th>Exemplo</th><th>Tratamento</th></tr>
 *   <tr><td>da <b>mensagem</b></td><td>JSON quebrado, campo faltando</td>
 *       <td>vai na hora para o {@code <tópico>.dlt}; o consumidor segue para a próxima</td></tr>
 *   <tr><td>do <b>ambiente</b></td><td>MongoDB fora do ar</td>
 *       <td>tenta de novo, com espera crescente (0,5 s, 1 s, 2 s... até 30 s), <b>sem desistir</b></td></tr>
 * </table>
 *
 * <p>Por que não desistir do erro de ambiente e mandar para a DLT depois de N tentativas: um
 * extrato atrasado é ruim, um extrato <b>sem um lançamento</b> é pior — o saldo e as linhas não
 * bateriam. Com o MongoDB fora, o consumidor para naquela mensagem e espera; o atraso aparece no
 * monitoramento (o <i>lag</i> do grupo), e nada se perde.
 *
 * <p><b>DLT</b> (<i>dead letter topic</i>, "tópico de mensagens mortas"): a mensagem original vai
 * para lá com cabeçalhos que dizem o erro, o tópico, a partição e o offset de origem.
 */
@Configuration(proxyBeanMethods = false)
class ConfiguracaoKafka {

    static final String SUFIXO_DLT = ".dlt";

    @Bean
    DefaultErrorHandler tratadorDeFalhasDoConsumidor(KafkaTemplate<?, ?> kafka) {
        // Mesma partição no .dlt: a DLT tem as mesmas 3 partições do tópico original.
        var paraDlt = new DeadLetterPublishingRecoverer(
                kafka, (registro, erro) -> new TopicPartition(registro.topic() + SUFIXO_DLT, registro.partition()));
        var espera = new ExponentialBackOff(500L, 2.0);
        espera.setMaxInterval(30_000L);
        var tratador = new DefaultErrorHandler(paraDlt, espera);
        tratador.addNotRetryableExceptions(EventoInvalidoException.class);
        return tratador;
    }
}
