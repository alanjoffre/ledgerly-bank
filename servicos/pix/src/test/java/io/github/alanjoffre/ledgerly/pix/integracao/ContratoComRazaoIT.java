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
import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.Razao;
import io.github.alanjoffre.ledgerly.pix.dominio.Passo;
import io.github.alanjoffre.ledgerly.pix.dominio.ResultadoDoPasso;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * O que o Pix espera do razão (ADR-0045). A chamada passa pelo adaptador de verdade: o contrato
 * descreve o que o {@code HttpRazao} manda e o que ele sabe ler. Quem confere o outro lado é o
 * {@code ContratoIT} do razão.
 */
@QuarkusTest
@WithTestResource(ContratoRecurso.class)
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "razao", pactVersion = PactSpecVersion.V3)
@MockServerConfig(port = ContratoRecurso.PORTA)
@DisplayName("Contrato: Pix → razão")
class ContratoComRazaoIT {

    private static final UUID CONTA = UUID.fromString("0a000000-0000-4000-8000-000000000001");
    private static final UUID ENVIO = UUID.fromString("0a000000-0000-4000-8000-0000000000e1");
    private static final UUID LANCAMENTO = UUID.fromString("0a000000-0000-4000-8000-0000000000f1");
    private static final String VALOR = "^\\d+\\.\\d{2}$";

    @Inject
    Razao razao;

    private static Map<String, String> chave(String valor) {
        return Map.of("Idempotency-Key", valor, "Content-Type", "application/json");
    }

    private static PactDslJsonBody movimento() {
        return new PactDslJsonBody()
                .uuid("contaId", CONTA)
                .stringMatcher("valor", VALOR, "30.00")
                .stringType("descricao", "Pix");
    }

    private static PactDslJsonBody lancamento() {
        return new PactDslJsonBody().uuid("id", LANCAMENTO);
    }

    @Pact(consumer = "pix")
    RequestResponsePact envio(PactDslWithProvider quem) {
        return quem.given("a conta tem saldo")
                .uponReceiving("um envio de Pix")
                .method("POST").path("/v1/pix/envios").headers(chave("contrato-envio")).body(movimento())
                .willRespondWith().status(201).body(lancamento())
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "envio")
    @DisplayName("envio: 201 com o id do lançamento")
    void envioFeito() {
        var r = razao.executar(Passo.DEBITAR, "contrato-envio", CONTA, Dinheiro.reais("30.00"), "Pix", null);
        assertThat(r).isEqualTo(new ResultadoDoPasso.Feito(LANCAMENTO));
    }

    @Pact(consumer = "pix")
    RequestResponsePact envioSemSaldo(PactDslWithProvider quem) {
        return quem.given("a conta está sem saldo")
                .uponReceiving("um envio de Pix maior que o saldo")
                .method("POST").path("/v1/pix/envios").headers(chave("contrato-sem-saldo")).body(movimento())
                .willRespondWith().status(422)
                        .headers(Map.of("Content-Type", "application/problem+json"))
                        .body(new PactDslJsonBody()
                        .stringValue("type", "urn:ledgerly:erro:saldo-insuficiente")
                        .stringType("detail", "Saldo insuficiente"))
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "envioSemSaldo")
    @DisplayName("envio sem saldo: 422 vira recusa, com o código do erro")
    void envioRecusado() {
        var r = razao.executar(Passo.DEBITAR, "contrato-sem-saldo", CONTA, Dinheiro.reais("30.00"), "Pix", null);
        assertThat(r).isInstanceOfSatisfying(ResultadoDoPasso.Recusado.class,
                recusa -> assertThat(recusa.codigo()).isEqualTo("saldo-insuficiente"));
    }

    private static PactDslJsonBody saidaDaLiquidacao() {
        return movimento().valueFromProviderState("lancamentoEnvioId", "${envioId}", ENVIO.toString());
    }

    @Pact(consumer = "pix")
    RequestResponsePact recebimento(PactDslWithProvider quem) {
        return quem.given("já houve um envio de Pix")
                .uponReceiving("o recebimento de um Pix")
                .method("POST").path("/v1/pix/recebimentos").headers(chave("contrato-recebimento"))
                .body(saidaDaLiquidacao())
                .willRespondWith().status(201).body(lancamento())
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "recebimento")
    @DisplayName("recebimento: leva o lançamento do envio; 201 com o id")
    void recebimentoFeito() {
        var r = razao.executar(Passo.CREDITAR, "contrato-recebimento", CONTA, Dinheiro.reais("30.00"), "Pix", ENVIO);
        assertThat(r).isEqualTo(new ResultadoDoPasso.Feito(LANCAMENTO));
    }

    @Pact(consumer = "pix")
    RequestResponsePact estorno(PactDslWithProvider quem) {
        return quem.given("já houve um envio de Pix")
                .uponReceiving("o estorno de um Pix")
                .method("POST").path("/v1/pix/estornos").headers(chave("contrato-estorno"))
                .body(saidaDaLiquidacao())
                .willRespondWith().status(201).body(lancamento())
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "estorno")
    @DisplayName("estorno: leva o lançamento do envio; 201 com o id")
    void estornoFeito() {
        var r = razao.executar(Passo.ESTORNAR, "contrato-estorno", CONTA, Dinheiro.reais("30.00"), "Pix", ENVIO);
        assertThat(r).isEqualTo(new ResultadoDoPasso.Feito(LANCAMENTO));
    }
}
