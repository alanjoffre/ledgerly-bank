package io.github.alanjoffre.ledgerly.seguranca;

import java.util.List;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;

/**
 * O token de serviço: o fluxo <i>client credentials</i> do OAuth2, para um serviço chamar outro
 * (ADR-0027). O serviço se apresenta ao Keycloak com o próprio id e segredo e recebe um token com
 * o papel {@code servico} — nenhuma pessoa no meio.
 *
 * <p>Dois cuidados que o padrão do Spring não tem:
 * <ul>
 *   <li><b>Gerente fora do pedido</b> ({@link AuthorizedClientServiceOAuth2AuthorizedClientManager}):
 *       o padrão de uma aplicação web guarda o token na sessão HTTP do pedido em andamento — e o
 *       serviço não tem sessão.</li>
 *   <li><b>Principal fixo</b>: o padrão guarda um token por pessoa logada (o principal do pedido).
 *       Com a "ana" e o "bruno" chamando, o contas pediria um token de serviço para cada um. O token
 *       é do serviço, não da pessoa: um só, reaproveitado até vencer.</li>
 * </ul>
 * Se o outro serviço responder 401 (o Keycloak trocou a chave, o token foi revogado), o token
 * guardado é descartado e o próximo pedido busca um novo.
 */
public final class TokenDeServico {

    /** O id do registro do cliente OAuth2 em {@code spring.security.oauth2.client.registration}. */
    public static final String REGISTRO = "ledgerly";

    private static final Authentication PRINCIPAL_DO_SERVICO = new AnonymousAuthenticationToken(
            "servico", "este-servico", List.of(new SimpleGrantedAuthority("ROLE_" + Papeis.SERVICO)));

    private TokenDeServico() {}

    public static OAuth2AuthorizedClientManager gerente(
            ClientRegistrationRepository registros, OAuth2AuthorizedClientService tokens) {
        var gerente = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registros, tokens);
        gerente.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return gerente;
    }

    /** Põe {@code Authorization: Bearer <token de serviço>} em todo pedido do {@code RestClient}. */
    public static OAuth2ClientHttpRequestInterceptor interceptador(
            OAuth2AuthorizedClientManager gerente, OAuth2AuthorizedClientService tokens) {
        var interceptador = new OAuth2ClientHttpRequestInterceptor(gerente);
        interceptador.setClientRegistrationIdResolver(pedido -> REGISTRO);
        interceptador.setPrincipalResolver(pedido -> PRINCIPAL_DO_SERVICO);
        interceptador.setAuthorizationFailureHandler(OAuth2ClientHttpRequestInterceptor.authorizationFailureHandler(tokens));
        return interceptador;
    }
}
