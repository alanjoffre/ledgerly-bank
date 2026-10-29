package io.github.alanjoffre.ledgerly.razao.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.alanjoffre.ledgerly.seguranca.teste.MockMvcComToken;
import io.github.alanjoffre.ledgerly.seguranca.teste.TokensDeTeste;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

/** O razão não atende pessoa nenhuma: só token de serviço (ADR-0027). */
@DisplayName("Segurança do razão")
@AutoConfigureMockMvc
class SegurancaIT extends TesteDeIntegracao {

    @Autowired
    private WebApplicationContext contexto;

    private MockMvcTester http;

    @BeforeEach
    void semTokenPadrao() {
        http = MockMvcComToken.semPadrao(contexto);
    }

    private MockMvcTester.MockMvcRequestBuilder deposito(UUID conta) {
        return http.post().uri("/v1/depositos").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contaId\":\"%s\",\"valor\":\"10.00\",\"descricao\":\"x\"}".formatted(conta));
    }

    @Test
    @DisplayName("sem token: 401, e nada é lançado")
    void semToken() {
        UUID conta = novaConta();
        assertThat(deposito(conta)).hasStatus(401);
        assertThat(saldo(conta).valor()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("cliente e analista: 403 — nem o dono da conta fala direto com o razão")
    void pessoa() {
        UUID conta = novaConta();
        assertThat(deposito(conta).header(HttpHeaders.AUTHORIZATION, TokensDeTeste.cliente(conta))).hasStatus(403);
        assertThat(http.get().uri("/v1/contas/{id}", conta).header(HttpHeaders.AUTHORIZATION, TokensDeTeste.analista()))
                .hasStatus(403);
        assertThat(saldo(conta).valor()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("serviço: 201")
    void servico() {
        UUID conta = novaConta();
        assertThat(deposito(conta).header(HttpHeaders.AUTHORIZATION, TokensDeTeste.servico())).hasStatus(201);
        assertThat(saldo(conta).valor()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("health: aberto, sem token")
    void saude() {
        assertThat(http.get().uri("/actuator/health")).hasStatusOk();
    }
}
