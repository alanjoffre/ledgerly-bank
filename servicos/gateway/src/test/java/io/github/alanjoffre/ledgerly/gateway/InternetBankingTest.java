package io.github.alanjoffre.ledgerly.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@DisplayName("Gateway do internet banking (BFF)")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("internet-banking")
class InternetBankingTest {

    private static final Map<String, ServicoFalso> SERVICOS = ServicoFalso.todos();
    private static final UUID CLIENTE = UUID.randomUUID();
    private static final String CONTA = UUID.randomUUID().toString();

    private static final Path TELA = telaConstruida();

    @DynamicPropertySource
    static void servicos(DynamicPropertyRegistry registro) {
        SERVICOS.forEach((nome, servico) -> registro.add("ledgerly.bff.servicos." + nome, () -> servico.endereco().toString()));
        registro.add("ledgerly.bff.pagina", () -> TELA.toUri().toString());
    }

    /** Uma "tela construída" mínima, como a que o Vite deixa em dist/. */
    private static Path telaConstruida() {
        try {
            Path pasta = Files.createTempDirectory("tela-ib");
            Files.writeString(pasta.resolve("index.html"), "<!doctype html><title>Ledgerly</title><script type=module src=/assets/app.js></script>");
            Files.createDirectories(pasta.resolve("assets"));
            Files.writeString(pasta.resolve("assets/app.js"), "console.log('ledgerly')");
            return pasta;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ClientRegistrationRepository registros;

    @Autowired
    OAuth2AuthorizedClientRepository tokens;

    Sessoes sessoes;

    @BeforeEach
    void preparar() {
        sessoes = new Sessoes(registros, tokens);
        SERVICOS.values().forEach(ServicoFalso::esquecer);
    }

    /** O que a tela faz: lê o cookie XSRF-TOKEN, que o gateway manda nas respostas, e o devolve no cabeçalho. */
    private String tokenDeCsrf(Sessoes.Logada quem) {
        var resposta = mvc.get().uri("/api/eu").session(quem.sessao()).with(authentication(quem.autenticacao())).exchange();
        Cookie cookie = resposta.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).as("o gateway entrega o cookie de CSRF").isNotNull();
        assertThat(cookie.isHttpOnly()).as("o JavaScript da tela precisa ler o cookie").isFalse();
        return cookie.getValue();
    }

    private MockHttpServletRequestBuilder comCsrf(MockHttpServletRequestBuilder pedido, Sessoes.Logada quem) {
        String xsrf = tokenDeCsrf(quem);
        return pedido.cookie(new Cookie("XSRF-TOKEN", xsrf)).header("X-XSRF-TOKEN", xsrf);
    }

    @Nested
    @DisplayName("Rotas")
    class Rotas {

        @ParameterizedTest(name = "{0} {1} vai ao {2}")
        @CsvSource({
            "GET, /api/v1/contas/" + "{conta}" + "/extrato?limite=5, extrato, /v1/contas/{conta}/extrato?limite=5",
            "GET, /api/v1/contas/{conta}/saldo, extrato, /v1/contas/{conta}/saldo",
            "GET, /api/v1/transferencias/abc, pix, /v1/transferencias/abc",
            "GET, /api/v1/chaves/ana@exemplo.test, pix, /v1/chaves/ana@exemplo.test",
            "GET, /api/v1/contas/{conta}/chaves, pix, /v1/contas/{conta}/chaves",
            "POST, /api/v1/qrcodes/leitura, pix, /v1/qrcodes/leitura",
            "GET, /api/v1/contas/{conta}, contas, /v1/contas/{conta}",
            "GET, /api/v1/clientes/{conta}/contas, contas, /v1/clientes/{conta}/contas",
            "POST, /api/v1/assistente/perguntas, assistente, /v1/assistente/perguntas",
        })
        @DisplayName("cada caminho vai ao serviço dono, sem o /api e com o token da sessão")
        void encaminha(String metodo, String caminho, String servico, String esperado) {
            var ana = sessoes.de("ana", CLIENTE, "cliente");
            var pedido = metodo.equals("GET")
                    ? get(caminho.replace("{conta}", CONTA))
                    : comCsrf(post(caminho.replace("{conta}", CONTA)), ana)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"payload\":\"x\"}");
            assertThat(mvc.perform(pedido.session(ana.sessao()).with(authentication(ana.autenticacao()))))
                    .hasStatusOk()
                    .bodyJson().extractingPath("$.servico").isEqualTo(servico);
            var recebido = SERVICOS.get(servico).ultimo();
            assertThat(recebido.caminho()).isEqualTo(esperado.replace("{conta}", CONTA));
            assertThat(recebido.authorization()).isEqualTo("Bearer " + ana.token());
            // O corpo nunca vai "em partes": a corrida do cliente HTTP do JDK (ADR-0031).
            assertThat(recebido.partes()).as("Transfer-Encoding").isNull();
            assertThat(recebido.tamanho()).as("Content-Length").isEqualTo(metodo.equals("GET") ? null : "15");
        }

        @Test
        @DisplayName("o cookie de sessão e o cabeçalho de CSRF não seguem para o serviço; o corpo segue")
        void naoEspalhaASessao() {
            var ana = sessoes.de("ana", CLIENTE, "cliente");
            String xsrf = tokenDeCsrf(ana);
            assertThat(mvc.post().uri("/api/v1/transferencias")
                            .session(ana.sessao())
                            .with(authentication(ana.autenticacao()))
                            .cookie(new Cookie("LEDGERLY_IB", "sessao-secreta"), new Cookie("XSRF-TOKEN", xsrf))
                            .header("X-XSRF-TOKEN", xsrf)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"valor\":\"10.00\"}"))
                    .hasStatusOk();
            var recebido = SERVICOS.get("pix").ultimo();
            assertThat(recebido.metodo()).isEqualTo("POST");
            assertThat(recebido.corpo()).isEqualTo("{\"valor\":\"10.00\"}");
            assertThat(recebido.tamanho()).isEqualTo("17");
            assertThat(recebido.partes()).isNull();
            assertThat(recebido.cookie()).isNull();
            assertThat(recebido.csrf()).isNull();
        }

        @Test
        @DisplayName("o razão não tem rota: só outro serviço fala com ele (ADR-0027)")
        void razaoNaoTemRota() {
            var ana = sessoes.de("ana", CLIENTE, "cliente");
            assertThat(mvc.get().uri("/api/v1/lancamentos").session(ana.sessao()).with(authentication(ana.autenticacao())))
                    .hasStatus(404);
            assertThat(SERVICOS.values()).allSatisfy(s -> assertThat(s.ultimo()).isNull());
        }
    }

    @Nested
    @DisplayName("Quem pode")
    class QuemPode {

        @Test
        @DisplayName("sem sessão, /api/** responde 401 em JSON — sem redirecionar para o login")
        void semSessao() {
            assertThat(mvc.get().uri("/api/v1/contas/" + CONTA))
                    .hasStatus(401)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .bodyJson().extractingPath("$.type").isEqualTo("urn:ledgerly:erro:nao-autenticado");
            assertThat(SERVICOS.get("contas").ultimo()).isNull();
        }

        @Test
        @DisplayName("o 401 da API não fica guardado para depois do login (senão o login voltaria para um JSON)")
        void semPedidoGuardado() {
            var resultado = mvc.get().uri("/api/eu").exchange();
            assertThat(resultado).hasStatus(401);
            var sessao = resultado.getRequest().getSession(false);
            assertThat(sessao == null ? null : sessao.getAttribute("SPRING_SECURITY_SAVED_REQUEST")).isNull();
        }

        @Test
        @DisplayName("o analista não usa o internet banking: 403 em JSON, e o pedido não sai do gateway")
        void papelDeOutroFrontEnd() {
            var carla = sessoes.de("carla", null, "analista");
            assertThat(mvc.get().uri("/api/v1/contas/" + CONTA).session(carla.sessao()).with(authentication(carla.autenticacao())))
                    .hasStatus(403)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .bodyJson().extractingPath("$.type").isEqualTo("urn:ledgerly:erro:acesso-negado");
            assertThat(SERVICOS.get("contas").ultimo()).isNull();
        }

        @Test
        @DisplayName("POST sem o token de CSRF: 403, e o Pix não sai do gateway")
        void semCsrf() {
            var ana = sessoes.de("ana", CLIENTE, "cliente");
            assertThat(mvc.post().uri("/api/v1/transferencias")
                            .session(ana.sessao())
                            .with(authentication(ana.autenticacao()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .hasStatus(403);
            assertThat(SERVICOS.get("pix").ultimo()).isNull();
        }

        @Test
        @DisplayName("a tela é aberta (não tem dado); quem decide mandar ao login é ela, pelo 401 da API")
        void telaAberta() {
            assertThat(mvc.get().uri("/").accept(MediaType.TEXT_HTML)).hasStatusOk();
        }

        @Test
        @DisplayName("a saúde do gateway é aberta")
        void saude() {
            assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
        }

        @Test
        @DisplayName("a CSP não deixa script embutido nem de outro site, nem a tela num iframe")
        void csp() {
            assertThat(ConfiguracaoDeSeguranca.CSP)
                    .contains("default-src 'self'", "frame-ancestors 'none'", "object-src 'none'", "base-uri 'none'")
                    .doesNotContain("unsafe-inline", "unsafe-eval", "*");
        }
    }

    @Nested
    @DisplayName("Login e saída")
    class LoginESaida {

        @Test
        @DisplayName("o login vai ao Keycloak com o cliente certo e PKCE (S256)")
        void loginComPkce() {
            var resposta = mvc.get().uri("/oauth2/authorization/keycloak").exchange();
            assertThat(resposta).hasStatus(302);
            String destino = resposta.getResponse().getRedirectedUrl();
            assertThat(destino)
                    .startsWith("http://localhost:18081/realms/ledgerly/protocol/openid-connect/auth?")
                    .contains("client_id=ledgerly-internet-banking")
                    .contains("code_challenge=")
                    .contains("code_challenge_method=S256")
                    .contains("scope=openid%20profile");
        }

        @Test
        @DisplayName("/api/eu diz quem está logado, sem o token")
        void eu() {
            var ana = sessoes.de("ana", CLIENTE, "cliente");
            var resposta = assertThat(mvc.get().uri("/api/eu").session(ana.sessao()).with(authentication(ana.autenticacao())));
            resposta.hasStatusOk();
            resposta.bodyJson().extractingPath("$.nome").isEqualTo("ana");
            resposta.bodyJson().extractingPath("$.papeis").asArray().containsExactly("cliente");
            resposta.bodyJson().extractingPath("$.clienteId").isEqualTo(CLIENTE.toString());
            resposta.body().asString().doesNotContain(ana.token());
        }

        @Test
        @DisplayName("/api/eu responde também a quem não tem o papel — a tela diz \"este acesso não é seu\"")
        void euDeOutroPapel() {
            var carla = sessoes.de("carla", null, "analista");
            var resposta = assertThat(mvc.get().uri("/api/eu").session(carla.sessao()).with(authentication(carla.autenticacao())));
            resposta.hasStatusOk();
            resposta.bodyJson().extractingPath("$.papeis").asArray().containsExactly("analista");
            resposta.bodyJson().extractingPath("$.clienteId").isNull();
        }

        @Test
        @DisplayName("sair apaga a sessão e devolve o endereço de saída do Keycloak, com volta para a página")
        void sair() {
            var ana = sessoes.de("ana", CLIENTE, "cliente");
            var resposta = assertThat(mvc.post().uri("/logout")
                    .session(ana.sessao())
                    .with(authentication(ana.autenticacao()))
                    .cookie(new Cookie("XSRF-TOKEN", "valor-da-tela"))
                    .header("X-XSRF-TOKEN", "valor-da-tela"));
            resposta.hasStatusOk();
            resposta.bodyJson().extractingPath("$.redirecionar").asString()
                    .startsWith("http://localhost:18081/realms/ledgerly/protocol/openid-connect/logout?")
                    .contains("id_token_hint=id-token-de-ana")
                    .contains("post_logout_redirect_uri=http://localhost/");
            assertThat(ana.sessao().isInvalid()).isTrue();
        }
    }

    @Nested
    @DisplayName("A tela (SPA)")
    class Tela {

        @Test
        @DisplayName("um endereço da tela devolve o index.html, sem cache — o roteador do React faz o resto")
        void enderecoDaTela() {
            assertThat(mvc.get().uri("/contas/" + CONTA + "/pix").accept(MediaType.TEXT_HTML))
                    .hasStatusOk()
                    .hasContentTypeCompatibleWith(MediaType.TEXT_HTML)
                    .hasHeader("Cache-Control", "no-cache")
                    .body().asString().contains("/assets/app.js");
        }

        @Test
        @DisplayName("os arquivos da tela saem da pasta construída")
        void arquivos() {
            assertThat(mvc.get().uri("/assets/app.js")).hasStatusOk().body().asString().contains("ledgerly");
        }

        @Test
        @DisplayName("arquivo que não existe é 404, não o index.html")
        void arquivoQueNaoExiste() {
            assertThat(mvc.get().uri("/assets/nao-existe.js").accept(MediaType.TEXT_HTML)).hasStatus(404);
        }

        @Test
        @DisplayName("/api/** nunca vira index.html, mesmo pedindo HTML")
        void apiNaoViraTela() {
            assertThat(mvc.get().uri("/api/v1/contas/" + CONTA).accept(MediaType.TEXT_HTML)).hasStatus(401);
        }

        @Test
        @DisplayName("/error nunca vira index.html (um erro sairia como 200 com a tela)")
        void erroNaoViraTela() {
            assertThat(mvc.get().uri("/error").accept(MediaType.TEXT_HTML)).body().asString().doesNotContain("/assets/app.js");
        }

        @Test
        @DisplayName("sem a tela construída, o gateway diz como construir")
        void semTela() throws IOException {
            Files.delete(TELA.resolve("index.html"));
            try {
                assertThat(mvc.get().uri("/").accept(MediaType.TEXT_HTML))
                        .hasStatus(404)
                        .body().asString().contains("npm run construir");
            } finally {
                Files.writeString(TELA.resolve("index.html"), "<!doctype html><script type=module src=/assets/app.js></script>");
            }
        }
    }

    @Nested
    @DisplayName("Cabeçalhos de segurança")
    class Cabecalhos {

        @Test
        @DisplayName("CSP só com 'self', sem Referer, sem cache e sem adivinhar o tipo do conteúdo")
        void cabecalhos() {
            var ana = sessoes.de("ana", CLIENTE, "cliente");
            assertThat(mvc.get().uri("/api/eu").session(ana.sessao()).with(authentication(ana.autenticacao())))
                    .hasHeader("Content-Security-Policy", ConfiguracaoDeSeguranca.CSP)
                    .hasHeader("Referrer-Policy", "no-referrer")
                    .hasHeader("X-Content-Type-Options", "nosniff")
                    .hasHeader("X-Frame-Options", "DENY")
                    .containsHeader("Cache-Control");
        }
    }
}
