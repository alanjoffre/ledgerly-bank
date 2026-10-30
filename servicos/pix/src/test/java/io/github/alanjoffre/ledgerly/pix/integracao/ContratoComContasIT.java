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
import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.Contas;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * O que o Pix espera do serviço de contas (ADR-0045). O CPF do contrato é só um exemplo que não passa na
 * validação: quem põe um CPF válido, na hora de conferir, é o provedor. Documento válido não vai em
 * arquivo versionado.
 */
@QuarkusTest
@WithTestResource(ContratoRecurso.class)
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "contas", pactVersion = PactSpecVersion.V3)
@MockServerConfig(port = ContratoRecurso.PORTA)
@DisplayName("Contrato: Pix → contas")
class ContratoComContasIT {

    private static final UUID CONTA = UUID.fromString("0a000000-0000-4000-8000-000000000002");
    private static final UUID CLIENTE = UUID.fromString("0a000000-0000-4000-8000-000000000003");
    private static final String INSTANTE = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$";
    private static final String CPF_DE_EXEMPLO = "00000000000";

    @Inject
    Contas contas;

    @Pact(consumer = "pix")
    RequestResponsePact titular(PactDslWithProvider quem) {
        return quem.given("existe uma conta ativa")
                .uponReceiving("o pedido do titular de uma conta")
                .method("GET").pathFromProviderState("/v1/contas/${contaId}/titular", "/v1/contas/" + CONTA + "/titular")
                .willRespondWith().status(200).body(new PactDslJsonBody()
                        .uuid("clienteId", CLIENTE)
                        .stringType("nome", "Ana Exemplo")
                        .stringValue("situacao", "ATIVA")
                        .stringMatcher("abertaEm", INSTANTE, "2026-10-30T12:00:00Z"))
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "titular")
    @DisplayName("titular: 200 com cliente, nome, situação e abertura")
    void titularDaConta() {
        var conta = contas.buscar(CONTA).orElseThrow();
        assertThat(conta.clienteId()).isEqualTo(CLIENTE);
        assertThat(conta.ativa()).isTrue();
        assertThat(conta.abertaEm()).isNotNull();
    }

    @Pact(consumer = "pix")
    RequestResponsePact titularDeContaInexistente(PactDslWithProvider quem) {
        return quem.given("a conta não existe")
                .uponReceiving("o pedido do titular de uma conta que não existe")
                .method("GET").path("/v1/contas/" + CONTA + "/titular")
                .willRespondWith().status(404)
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "titularDeContaInexistente")
    @DisplayName("titular de conta inexistente: 404 vira vazio")
    void contaInexistente() {
        assertThat(contas.buscar(CONTA)).isEmpty();
    }

    @Pact(consumer = "pix")
    RequestResponsePact titularidade(PactDslWithProvider quem) {
        return quem.given("existe uma conta ativa")
                .uponReceiving("a conferência do CPF do titular")
                .method("POST").pathFromProviderState("/v1/contas/${contaId}/titularidade", "/v1/contas/" + CONTA + "/titularidade")
                .headers("Content-Type", "application/json")
                .body(new PactDslJsonBody().valueFromProviderState("cpf", "${cpf}", CPF_DE_EXEMPLO))
                .willRespondWith().status(200).body(new PactDslJsonBody().booleanValue("confere", true))
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "titularidade")
    @DisplayName("titularidade: 200 com o resultado da conferência")
    void cpfConfere() {
        assertThat(contas.titularidadeConfere(CONTA, CPF_DE_EXEMPLO)).isTrue();
    }
}
