package io.github.alanjoffre.ledgerly.extrato.integracao;

import static org.awaitility.Awaitility.await;

import io.github.alanjoffre.ledgerly.extrato.aplicacao.ConsultaDoExtrato;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.PaginaDoExtrato;
import io.github.alanjoffre.ledgerly.extrato.dominio.Saldo;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.core.ThrowingRunnable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Base dos testes de integração: Spring de verdade + Kafka e MongoDB de verdade.
 *
 * <p>Não há limpeza entre os testes: cada teste usa contas e lançamentos com UUID novo, então um
 * não enxerga o outro — como no mundo real, onde o tópico guarda tudo.
 */
@SpringBootTest
@Import(ContainersDeTeste.class)
abstract class TesteDeIntegracao {

    /** A projeção é assíncrona: o teste espera até este tempo pelo resultado. */
    protected static final Duration ESPERA = Duration.ofSeconds(30);

    @Autowired
    protected KafkaTemplate<String, String> kafka;

    @Autowired
    protected ConsultaDoExtrato consulta;

    /** Publica e espera o Kafka confirmar — como o publicador do Outbox faz. */
    protected void publicar(String topico, UUID chave, String mensagem) {
        try {
            kafka.send(topico, chave.toString(), mensagem).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao publicar no Kafka", e);
        }
    }

    protected void publicarLancamento(UUID lancamentoId, String mensagem) {
        publicar(MensagensDoContrato.TOPICO_LANCAMENTOS, lancamentoId, mensagem);
    }

    protected void publicarPix(UUID pixId, String mensagem) {
        publicar(MensagensDoContrato.TOPICO_PIX, pixId, mensagem);
    }

    protected static void esperar(ThrowingRunnable verificacao) {
        await().atMost(ESPERA).pollInterval(Duration.ofMillis(50)).untilAsserted(verificacao);
    }

    protected PaginaDoExtrato extrato(UUID conta) {
        return consulta.extrato(conta, 500, null);
    }

    protected Optional<Saldo> saldo(UUID conta) {
        try {
            return Optional.of(consulta.saldo(conta));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
