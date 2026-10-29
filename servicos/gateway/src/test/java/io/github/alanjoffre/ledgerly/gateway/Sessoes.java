package io.github.alanjoffre.ledgerly.gateway;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Monta uma sessão "já logada", como a que o gateway teria depois do login no Keycloak: a pessoa
 * no contexto de segurança e o token de acesso guardado na sessão HTTP.
 */
final class Sessoes {

    record Logada(MockHttpSession sessao, OAuth2AuthenticationToken autenticacao, String token) {}

    private final ClientRegistrationRepository registros;
    private final OAuth2AuthorizedClientRepository tokens;

    Sessoes(ClientRegistrationRepository registros, OAuth2AuthorizedClientRepository tokens) {
        this.registros = registros;
        this.tokens = tokens;
    }

    Logada de(String nome, UUID clienteId, String... papeis) {
        String token = tokenDeAcesso(nome, clienteId, papeis);
        OidcUser usuario = ConfiguracaoDeLogin.comPapeis(
                new DefaultOidcUser(AuthorityUtils.createAuthorityList("OIDC_USER"), idToken(nome), "preferred_username"),
                DoToken.ler(token));
        var autenticacao = new OAuth2AuthenticationToken(usuario, usuario.getAuthorities(), ConfiguracaoDeLogin.REGISTRO);
        var sessao = new MockHttpSession();
        var pedido = new MockHttpServletRequest();
        pedido.setSession(sessao);
        var cliente = new OAuth2AuthorizedClient(
                registros.findByRegistrationId(ConfiguracaoDeLogin.REGISTRO),
                nome,
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, token, Instant.now(), Instant.now().plusSeconds(300)));
        tokens.saveAuthorizedClient(cliente, new TestingAuthenticationToken(nome, null), pedido, new MockHttpServletResponse());
        return new Logada(sessao, autenticacao, token);
    }

    static OidcIdToken idToken(String nome) {
        return OidcIdToken.withTokenValue("id-token-de-" + nome)
                .subject("sub-" + nome)
                .claim("preferred_username", nome)
                .issuer("http://localhost:18081/realms/ledgerly")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    static String tokenDeAcesso(String nome, UUID clienteId, String... papeis) {
        var claims = new JWTClaimsSet.Builder()
                .subject("sub-" + nome)
                .claim("preferred_username", nome)
                .claim("realm_access", Map.of("roles", List.of(papeis)));
        if (clienteId != null) {
            claims.claim("cliente_id", clienteId.toString());
        }
        return new PlainJWT(claims.build()).serialize();
    }
}
