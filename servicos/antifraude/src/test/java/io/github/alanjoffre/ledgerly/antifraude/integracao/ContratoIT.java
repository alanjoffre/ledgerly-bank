package io.github.alanjoffre.ledgerly.antifraude.integracao;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * O antifraude cumpre o que o Pix espera dele (ADR-0045): cada interação de
 * {@code contratos/pix-antifraude.json} é repetida contra o serviço de verdade.
 */
@QuarkusTest
@TestSecurity(user = "ledgerly-pix", roles = "servico")
@Provider("antifraude")
@PactFolder("../../contratos")
@DisplayName("Contrato: o antifraude cumpre")
class ContratoIT {

    @BeforeEach
    void alvo(PactVerificationContext contexto) {
        contexto.setTarget(new HttpTestTarget("localhost", RestAssured.port));
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void confere(PactVerificationContext contexto) {
        contexto.verifyInteraction();
    }

    @State("o antifraude está no ar")
    void noAr() {
        // Nada a preparar: um Pix sem histórico, de valor baixo, para uma conta antiga.
    }
}
