package io.github.alanjoffre.ledgerly.contas.integracao;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import com.jayway.jsonpath.JsonPath;
import io.github.alanjoffre.ledgerly.contas.dominio.CpfsDeTeste;
import io.github.alanjoffre.ledgerly.seguranca.teste.TokensDeTeste;
import java.util.Map;
import org.apache.hc.core5.http.HttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * O serviço de contas cumpre o que o Pix espera dele (ADR-0045): cada interação de
 * {@code contratos/pix-contas.json} é repetida contra o serviço de verdade, com banco de verdade.
 */
@Provider("contas")
@PactFolder("../../contratos")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Contrato: o serviço de contas cumpre")
class ContratoIT extends TesteDeIntegracao {

    @LocalServerPort
    private int porta;

    @Autowired
    private MockMvcTester comoServico;

    @BeforeEach
    void alvo(PactVerificationContext contexto) {
        contexto.setTarget(new HttpTestTarget("localhost", porta));
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void confere(PactVerificationContext contexto, HttpRequest pedido) {
        pedido.setHeader("Authorization", TokensDeTeste.servico());
        contexto.verifyInteraction();
    }

    @State("existe uma conta ativa")
    Map<String, Object> contaAtiva() throws Exception {
        String cpf = CpfsDeTeste.gerar();
        String cliente = id(comoServico.post().uri("/v1/clientes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"Ana Exemplo\",\"cpf\":\"%s\"}".formatted(cpf)).exchange().getResponse()
                .getContentAsString());
        String conta = id(comoServico.post().uri("/v1/contas").contentType(MediaType.APPLICATION_JSON)
                .content("{\"clienteId\":\"%s\"}".formatted(cliente)).exchange().getResponse().getContentAsString());
        return Map.of("contaId", conta, "cpf", cpf);
    }

    @State("a conta não existe")
    void contaInexistente() {
        // O banco já foi limpo antes do teste.
    }

    private static String id(String corpo) {
        return JsonPath.read(corpo, "$.id");
    }
}
