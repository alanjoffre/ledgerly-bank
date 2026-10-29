package io.github.alanjoffre.ledgerly.seguranca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Um Keycloak e um serviço falsos, num servidor HTTP do próprio JDK: o token é pedido UMA vez,
 * reaproveitado para pessoas diferentes, e trocado depois de um 401.
 */
@DisplayName("Token de serviço (client credentials)")
class TokenDeServicoTest {

    private HttpServer servidor;
    private final AtomicInteger tokensEmitidos = new AtomicInteger();
    private final List<String> corposDoToken = new CopyOnWriteArrayList<>();
    private final List<String> autorizacoesRecebidas = new CopyOnWriteArrayList<>();
    private volatile boolean recusarProximo;
    private RestClient cliente;

    @BeforeEach
    void subir() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/token", troca -> {
            corposDoToken.add(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            responder(troca, 200, """
                    {"access_token":"token-%d","token_type":"Bearer","expires_in":300}"""
                    .formatted(tokensEmitidos.incrementAndGet()));
        });
        servidor.createContext("/recurso", troca -> {
            autorizacoesRecebidas.add(troca.getRequestHeaders().getFirst("Authorization"));
            boolean recusar = recusarProximo;
            recusarProximo = false;
            responder(troca, recusar ? 401 : 200, "{}");
        });
        servidor.start();
        String base = "http://127.0.0.1:" + servidor.getAddress().getPort();

        var registros = new InMemoryClientRegistrationRepository(ClientRegistration
                .withRegistrationId(TokenDeServico.REGISTRO)
                .clientId("ledgerly-teste").clientSecret("segredo")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(base + "/token").build());
        var tokens = new InMemoryOAuth2AuthorizedClientService(registros);
        cliente = RestClient.builder().baseUrl(base)
                .requestInterceptor(TokenDeServico.interceptador(TokenDeServico.gerente(registros, tokens), tokens))
                .build();
    }

    @AfterEach
    void descer() {
        servidor.stop(0);
        SecurityContextHolder.clearContext();
    }

    private static void responder(HttpExchange troca, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        troca.getResponseHeaders().add("Content-Type", "application/json");
        troca.sendResponseHeaders(status, bytes.length);
        troca.getResponseBody().write(bytes);
        troca.close();
    }

    private void chamarComo(String pessoa) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(pessoa, "x", "ROLE_cliente"));
        cliente.get().uri("/recurso").retrieve().toBodilessEntity();
    }

    @Test
    @DisplayName("um token só para o serviço, mesmo com pessoas diferentes no pedido")
    void reaproveita() {
        chamarComo("ana");
        chamarComo("bruno");
        SecurityContextHolder.clearContext();
        cliente.get().uri("/recurso").retrieve().toBodilessEntity();

        assertThat(tokensEmitidos).hasValue(1);
        assertThat(corposDoToken).singleElement().asString().contains("grant_type=client_credentials");
        assertThat(autorizacoesRecebidas).hasSize(3).containsOnly("Bearer token-1");
    }

    @Test
    @DisplayName("depois de um 401, descarta o token e o próximo pedido busca outro")
    void trocaDepoisDe401() {
        chamarComo("ana");
        recusarProximo = true;
        assertThatThrownBy(() -> chamarComo("ana")).isInstanceOf(HttpClientErrorException.Unauthorized.class);
        chamarComo("ana");

        assertThat(tokensEmitidos).hasValue(2);
        assertThat(autorizacoesRecebidas).containsExactly("Bearer token-1", "Bearer token-1", "Bearer token-2");
    }
}
