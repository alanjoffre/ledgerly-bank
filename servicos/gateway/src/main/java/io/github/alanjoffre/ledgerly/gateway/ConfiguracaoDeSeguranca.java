package io.github.alanjoffre.ledgerly.gateway;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * A segurança do BFF.
 *
 * <ul>
 *   <li><b>Login</b>: authorization code + PKCE com o Keycloak; a sessão fica num cookie HttpOnly
 *       (o JavaScript não lê), SameSite=Lax.</li>
 *   <li><b>A tela</b> (HTML, JS, CSS) é aberta; os dados não.</li>
 *   <li><b>/api/**</b>: exige o papel deste front-end. Sem sessão, <b>401</b> — sem redirecionar
 *       para o login, que numa chamada do JavaScript viraria um HTML estranho; a tela é que decide
 *       mandar para o login. Papel errado ou CSRF faltando, <b>403</b>. Os dois em JSON, escritos
 *       aqui mesmo: um {@code sendError} iria para {@code /error}.</li>
 *   <li>Nenhum pedido fica guardado para "depois do login": a tela é aberta, então não há o que
 *       retomar — e guardar um {@code /api/eu} que deu 401 faria o login terminar num JSON.</li>
 *   <li><b>CSRF</b> de SPA: o token vai num cookie legível ({@code XSRF-TOKEN}) e volta no cabeçalho
 *       {@code X-XSRF-TOKEN}; um site de fora não consegue ler o cookie, então não forja o POST.</li>
 *   <li><b>Sair</b>: apaga a sessão aqui e encerra a do Keycloak (RP-initiated logout).</li>
 * </ul>
 */
@Configuration
class ConfiguracaoDeSeguranca {

    /**
     * Content Security Policy: o navegador só roda script, estilo e imagem que vierem daqui mesmo
     * ('self') — nada de script embutido nem de outro site. Se um XSS conseguir injetar um
     * {@code <script>}, ele não roda. {@code frame-ancestors 'none'}: ninguém põe a tela num iframe
     * (clickjacking).
     */
    static final String CSP = "default-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; "
            + "form-action 'self'; frame-ancestors 'none'";

    private static final PathPatternRequestMatcher API = PathPatternRequestMatcher.withDefaults().matcher("/api/**");

    @Bean
    SecurityFilterChain seguranca(HttpSecurity http, PropriedadesDoBff bff, ClientRegistrationRepository registros)
            throws Exception {
        var pedidoDeLogin = new DefaultOAuth2AuthorizationRequestResolver(registros, "/oauth2/authorization");
        pedidoDeLogin.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        http.authorizeHttpRequests(regras -> regras
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/eu").authenticated()
                        .requestMatchers("/api/**").hasRole(bff.papel())
                        // A tela (HTML, JS e CSS) é aberta: não tem dado nenhum. Os dados vêm de
                        // /api/**, que exige sessão — sem ela, a tela mostra o botão "Entrar".
                        .anyRequest().permitAll())
                .oauth2Login(login -> login.authorizationEndpoint(e -> e.authorizationRequestResolver(pedidoDeLogin)))
                .logout(sair -> sair.logoutSuccessHandler(new SairNoKeycloak(registros)))
                .csrf(csrf -> csrf.spa())
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .exceptionHandling(erros -> erros
                        .defaultAuthenticationEntryPointFor(ConfiguracaoDeSeguranca::naoAutenticado, API)
                        .defaultAccessDeniedHandlerFor(ConfiguracaoDeSeguranca::acessoNegado, API))
                .headers(cabecalhos -> cabecalhos
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER)));
        return http.build();
    }

    private static void naoAutenticado(HttpServletRequest pedido, HttpServletResponse resposta, Exception erro)
            throws IOException {
        problema(resposta, 401, "nao-autenticado", "Não autenticado", "Faça login.");
    }

    private static void acessoNegado(HttpServletRequest pedido, HttpServletResponse resposta, AccessDeniedException erro)
            throws IOException {
        problema(resposta, 403, "acesso-negado", "Acesso negado", erro instanceof CsrfException
                ? "Token de CSRF ausente ou inválido: recarregue a página."
                : "O seu papel não permite esta operação.");
    }

    /** Erro em "problem details" (RFC 9457), o mesmo formato dos serviços. Os textos são fixos. */
    private static void problema(HttpServletResponse resposta, int status, String tipo, String titulo, String detalhe)
            throws IOException {
        resposta.setStatus(status);
        resposta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        resposta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resposta.getWriter().write("""
                {"type":"urn:ledgerly:erro:%s","title":"%s","status":%d,"detail":"%s"}""".formatted(tipo, titulo, status, detalhe));
    }

    /**
     * Sair: a resposta é um JSON com o endereço de saída do Keycloak, e a tela navega para ele. Um
     * redirecionamento direto não serviria: o "sair" é um POST do JavaScript (com o cabeçalho de
     * CSRF), e o {@code fetch} não leva a página junto num redirecionamento.
     */
    static final class SairNoKeycloak extends OidcClientInitiatedLogoutSuccessHandler {

        SairNoKeycloak(ClientRegistrationRepository registros) {
            super(registros);
            setPostLogoutRedirectUri("{baseUrl}/");
        }

        @Override
        public void onLogoutSuccess(HttpServletRequest pedido, HttpServletResponse resposta, Authentication quem)
                throws IOException {
            String destino = determineTargetUrl(pedido, resposta, quem);
            resposta.setStatus(200);
            resposta.setContentType(MediaType.APPLICATION_JSON_VALUE);
            resposta.setCharacterEncoding(StandardCharsets.UTF_8.name());
            resposta.getWriter().write("{\"redirecionar\":\"" + destino.replace("\"", "%22") + "\"}");
        }
    }
}
