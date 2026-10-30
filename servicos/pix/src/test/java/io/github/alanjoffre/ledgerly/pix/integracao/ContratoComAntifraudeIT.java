package io.github.alanjoffre.ledgerly.pix.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit.MockServerConfig;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.Antifraude;
import io.github.alanjoffre.ledgerly.pix.dominio.ResultadoDaAvaliacao;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** O que o Pix espera do antifraude (ADR-0045): o pedido de avaliação e o que ele lê da resposta. */
@QuarkusTest
@WithTestResource(ContratoRecurso.class)
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "antifraude", pactVersion = PactSpecVersion.V3)
@MockServerConfig(port = ContratoRecurso.PORTA)
@DisplayName("Contrato: Pix → antifraude")
class ContratoComAntifraudeIT {

    private static final UUID TRANSFERENCIA = UUID.fromString("0a000000-0000-4000-8000-000000000004");
    private static final UUID PAGADOR = UUID.fromString("0a000000-0000-4000-8000-000000000005");
    private static final UUID RECEBEDOR = UUID.fromString("0a000000-0000-4000-8000-000000000006");
    private static final Instant TARDE = Instant.parse("2026-10-29T17:00:00Z");
    private static final Instant CONTA_ANTIGA = Instant.parse("2024-01-10T12:00:00Z");

    @Inject
    Antifraude antifraude;

    private static PactDslJsonBody pedido() {
        return new PactDslJsonBody()
                .uuid("transferenciaId", TRANSFERENCIA)
                .uuid("pagadorContaId", PAGADOR)
                .uuid("recebedorContaId", RECEBEDOR)
                .stringMatcher("valor", "^\\d+\\.\\d{2}$", "90.00")
                .stringType("instante", TARDE.toString())
                .stringType("contaRecebedoraAbertaEm", CONTA_ANTIGA.toString());
    }

    @Pact(consumer = "pix")
    RequestResponsePact avaliacao(PactDslWithProvider quem) {
        return quem.given("o antifraude está no ar")
                .uponReceiving("o pedido de avaliação de um Pix de baixo risco")
                .method("POST").path("/v1/avaliacoes").headers("Content-Type", "application/json").body(pedido())
                .willRespondWith().status(200).body(new PactDslJsonBody()
                        .integerType("nota", 40)
                        .stringValue("decisao", "APROVAR"))
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "avaliacao")
    @DisplayName("avaliação: 200 com nota e decisão")
    void aprovada() {
        var r = antifraude.avaliar(new Antifraude.Pedido(
                TRANSFERENCIA, PAGADOR, RECEBEDOR, Dinheiro.reais("90.00"), TARDE, CONTA_ANTIGA));
        assertThat(r).isEqualTo(new ResultadoDaAvaliacao.Aprovar(40));
    }
}
