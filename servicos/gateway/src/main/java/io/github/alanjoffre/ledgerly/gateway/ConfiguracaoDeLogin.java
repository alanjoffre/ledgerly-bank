package io.github.alanjoffre.ledgerly.gateway;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * O login OIDC com o Keycloak (authorization code + PKCE) e onde mora o token de cada sessão.
 *
 * <p>Os endereços do Keycloak são escritos aqui, sem a "descoberta" ({@code issuer-uri}): assim o
 * gateway sobe mesmo com o Keycloak fora do ar, e o primeiro login é que precisa dele.
 */
@Configuration
class ConfiguracaoDeLogin {

    static final String REGISTRO = "keycloak";

    @Bean
    ClientRegistrationRepository registros(PropriedadesDoBff bff) {
        String oidc = bff.emissor() + "/protocol/openid-connect";
        ClientRegistration keycloak = ClientRegistration.withRegistrationId(REGISTRO)
                .clientName("Keycloak")
                .clientId(bff.clienteId())
                .clientSecret(bff.clienteSegredo())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile")
                .issuerUri(bff.emissor())
                .authorizationUri(oidc + "/auth")
                .tokenUri(oidc + "/token")
                .jwkSetUri(oidc + "/certs")
                .userNameAttributeName("preferred_username")
                // O endereço de "sair" do Keycloak (RP-initiated logout), que a descoberta traria.
                .providerConfigurationMetadata(Map.of("end_session_endpoint", oidc + "/logout"))
                .build();
        return new InMemoryClientRegistrationRepository(keycloak);
    }

    /**
     * O token fica na sessão HTTP (no servidor), não num mapa global: sair apaga a sessão e o token
     * junto. O navegador só tem o cookie de sessão.
     */
    @Bean
    OAuth2AuthorizedClientRepository tokensDaSessao() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    /** Quem entrega o token ao TokenRelay — e o renova com o refresh token quando vence. */
    @Bean
    OAuth2AuthorizedClientManager gerenteDeTokens(
            ClientRegistrationRepository registros, OAuth2AuthorizedClientRepository tokens) {
        var gerente = new DefaultOAuth2AuthorizedClientManager(registros, tokens);
        gerente.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .authorizationCode()
                .refreshToken()
                .build());
        return gerente;
    }

    /** A pessoa logada, com os papéis do realm ({@code ROLE_cliente}...) e o {@code cliente_id}. */
    @Bean
    OAuth2UserService<OidcUserRequest, OidcUser> usuarios() {
        OidcUserService padrao = new OidcUserService();
        return pedido -> comPapeis(padrao.loadUser(pedido), DoToken.ler(pedido.getAccessToken().getTokenValue()));
    }

    static OidcUser comPapeis(OidcUser usuario, DoToken token) {
        Set<GrantedAuthority> autoridades = new HashSet<>(usuario.getAuthorities());
        token.papeis().forEach(p -> autoridades.add(new SimpleGrantedAuthority("ROLE_" + p)));
        Map<String, Object> extras = new HashMap<>();
        extras.put("sub", usuario.getSubject());
        token.clienteId().ifPresent(id -> extras.put("cliente_id", id.toString()));
        return new DefaultOidcUser(autoridades, usuario.getIdToken(), new OidcUserInfo(extras), "preferred_username");
    }
}
