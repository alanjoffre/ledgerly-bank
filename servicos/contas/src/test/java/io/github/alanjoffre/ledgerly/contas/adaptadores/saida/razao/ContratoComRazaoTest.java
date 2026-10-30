package io.github.alanjoffre.ledgerly.contas.adaptadores.saida.razao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import io.github.alanjoffre.ledgerly.contas.dominio.EncerramentoComSaldoException;
import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.web.client.RestClient;

/** O que o serviço de contas espera do razão (ADR-0045), passando pelo adaptador de verdade. */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "razao", pactVersion = PactSpecVersion.V3)
@DisplayName("Contrato: contas → razão")
class ContratoComRazaoTest {

    private static final UUID CONTA = UUID.fromString("0b000000-0000-4000-8000-000000000001");

    private static HttpRazao razao(MockServer servidor) {
        return new HttpRazao(RestClient.builder().baseUrl(servidor.getUrl()).build());
    }

    @Pact(consumer = "contas")
    RequestResponsePact saldo(PactDslWithProvider quem) {
        return quem.given("a conta existe no razão com saldo")
                .uponReceiving("a consulta de uma conta")
                .method("GET").path("/v1/contas/" + CONTA)
                .willRespondWith().status(200).body(new PactDslJsonBody()
                        .stringValue("moeda", "BRL")
                        .stringMatcher("saldo", "^\\d+\\.\\d{2}$", "100.00"))
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "saldo")
    @DisplayName("saldo: 200 com moeda e saldo em texto")
    void consultaSaldo(MockServer servidor) {
        assertThat(razao(servidor).consultarSaldo(CONTA)).contains(Dinheiro.reais("100.00"));
    }

    @Pact(consumer = "contas")
    RequestResponsePact contaDesconhecida(PactDslWithProvider quem) {
        return quem.given("a conta não existe no razão")
                .uponReceiving("a consulta de uma conta que o razão ainda não conhece")
                .method("GET").path("/v1/contas/" + CONTA)
                .willRespondWith().status(404)
                        .headers(Map.of("Content-Type", "application/problem+json"))
                        .body(new PactDslJsonBody()
                        .stringValue("type", "urn:ledgerly:erro:conta-nao-encontrada"))
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "contaDesconhecida")
    @DisplayName("conta desconhecida: 404 com o tipo do erro vira vazio")
    void contaAindaNaoChegou(MockServer servidor) {
        assertThat(razao(servidor).consultarSaldo(CONTA)).isEmpty();
    }

    private static PactDslJsonBody encerrada() {
        return new PactDslJsonBody().stringValue("situacao", "ENCERRADA");
    }

    @Pact(consumer = "contas")
    RequestResponsePact encerramento(PactDslWithProvider quem) {
        return quem.given("a conta existe no razão com saldo zero")
                .uponReceiving("o encerramento de uma conta com saldo zero")
                .method("PUT").path("/v1/contas/" + CONTA + "/situacao")
                .headers("Content-Type", "application/json").body(encerrada())
                .willRespondWith().status(200)
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "encerramento")
    @DisplayName("encerramento: 200")
    void encerra(MockServer servidor) {
        assertThatCode(() -> razao(servidor).encerrarConta(CONTA)).doesNotThrowAnyException();
    }

    @Pact(consumer = "contas")
    RequestResponsePact encerramentoComSaldo(PactDslWithProvider quem) {
        return quem.given("a conta existe no razão com saldo")
                .uponReceiving("o encerramento de uma conta com saldo")
                .method("PUT").path("/v1/contas/" + CONTA + "/situacao")
                .headers("Content-Type", "application/json").body(encerrada())
                .willRespondWith().status(422)
                        .headers(Map.of("Content-Type", "application/problem+json"))
                        .body(new PactDslJsonBody()
                        .stringValue("type", "urn:ledgerly:erro:encerramento-com-saldo")
                        .stringType("detail", "A conta tem saldo"))
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "encerramentoComSaldo")
    @DisplayName("encerramento com saldo: 422 vira a regra de negócio")
    void recusaEncerrar(MockServer servidor) {
        assertThatThrownBy(() -> razao(servidor).encerrarConta(CONTA))
                .isInstanceOf(EncerramentoComSaldoException.class);
    }
}
