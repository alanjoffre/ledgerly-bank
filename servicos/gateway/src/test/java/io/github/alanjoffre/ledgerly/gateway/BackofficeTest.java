package io.github.alanjoffre.ledgerly.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@DisplayName("Gateway do backoffice de fraude (BFF)")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("backoffice")
class BackofficeTest {

    private static final Map<String, ServicoFalso> SERVICOS = ServicoFalso.todos();

    @DynamicPropertySource
    static void servicos(DynamicPropertyRegistry registro) {
        SERVICOS.forEach((nome, servico) -> registro.add("ledgerly.bff.servicos." + nome, () -> servico.endereco().toString()));
        registro.add("ledgerly.bff.pagina", () -> "file:pasta-que-nao-existe/");
    }

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ClientRegistrationRepository registros;

    @Autowired
    OAuth2AuthorizedClientRepository tokens;

    @Test
    @DisplayName("a analista vê a fila de análise e devolve o Pix, com o token dela")
    void analista() {
        var carla = new Sessoes(registros, tokens).de("carla", null, "analista");
        assertThat(mvc.get().uri("/api/v1/transferencias?situacao=EM_ANALISE")
                        .session(carla.sessao())
                        .with(authentication(carla.autenticacao())))
                .hasStatusOk();
        assertThat(SERVICOS.get("pix").ultimo().caminho()).isEqualTo("/v1/transferencias?situacao=EM_ANALISE");

        String id = UUID.randomUUID().toString();
        assertThat(mvc.post().uri("/api/v1/transferencias/" + id + "/devolucao")
                        .session(carla.sessao())
                        .with(authentication(carla.autenticacao()))
                        .cookie(new Cookie("XSRF-TOKEN", "valor-da-tela"))
                        .header("X-XSRF-TOKEN", "valor-da-tela")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"justificativa\":\"golpe confirmado\"}"))
                .hasStatusOk();
        var recebido = SERVICOS.get("pix").ultimo();
        assertThat(recebido.caminho()).isEqualTo("/v1/transferencias/" + id + "/devolucao");
        assertThat(recebido.authorization()).isEqualTo("Bearer " + carla.token());
    }

    @Test
    @DisplayName("a analista lê a avaliação do antifraude de um Pix retido; pedir uma avaliação não passa do gateway")
    void avaliacaoDoAntifraude() {
        var carla = new Sessoes(registros, tokens).de("carla", null, "analista");
        String id = UUID.randomUUID().toString();
        assertThat(mvc.get().uri("/api/v1/avaliacoes/" + id)
                        .session(carla.sessao())
                        .with(authentication(carla.autenticacao())))
                .hasStatusOk();
        var recebido = SERVICOS.get("antifraude").ultimo();
        assertThat(recebido.caminho()).isEqualTo("/v1/avaliacoes/" + id);
        assertThat(recebido.authorization()).isEqualTo("Bearer " + carla.token());
        assertThat(mvc.get().uri("/api/v1/avaliacoes/" + id + "/explicacao")
                        .session(carla.sessao())
                        .with(authentication(carla.autenticacao())))
                .hasStatusOk();
        assertThat(SERVICOS.get("antifraude").ultimo().caminho()).isEqualTo("/v1/avaliacoes/" + id + "/explicacao");

        SERVICOS.get("antifraude").esquecer();
        assertThat(mvc.post().uri("/api/v1/avaliacoes")
                        .session(carla.sessao())
                        .with(authentication(carla.autenticacao()))
                        .cookie(new Cookie("XSRF-TOKEN", "valor-da-tela"))
                        .header("X-XSRF-TOKEN", "valor-da-tela")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .hasStatus4xxClientError();
        assertThat(SERVICOS.get("antifraude").ultimo()).isNull();
    }

    @Test
    @DisplayName("o cliente não usa o backoffice: 403 em JSON")
    void cliente() {
        var ana = new Sessoes(registros, tokens).de("ana", UUID.randomUUID(), "cliente");
        assertThat(mvc.get().uri("/api/v1/transferencias?situacao=EM_ANALISE")
                        .session(ana.sessao())
                        .with(authentication(ana.autenticacao())))
                .hasStatus(403)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.type").isEqualTo("urn:ledgerly:erro:acesso-negado");
    }

    @Test
    @DisplayName("a tela do backoffice vem da pasta dele (aqui, não construída)")
    void tela() {
        assertThat(mvc.get().uri("/").accept(MediaType.TEXT_HTML)).hasStatus(404);
    }

    @Test
    @DisplayName("o login vai ao Keycloak com o cliente do backoffice")
    void login() {
        assertThat(mvc.get().uri("/oauth2/authorization/keycloak"))
                .hasStatus(302)
                .redirectedUrl().contains("client_id=ledgerly-backoffice");
    }
}
