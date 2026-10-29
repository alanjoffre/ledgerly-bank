package io.github.alanjoffre.ledgerly.seguranca;

import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * O decodificador de token: confere assinatura, validade, emissor e audiência.
 *
 * <p>Produção e testes usam os MESMOS validadores ({@link #validar}); só muda de onde vem a chave
 * pública — do Keycloak (JWKS) ou da memória ({@code TokensDeTeste}).
 */
public final class Decodificadores {

    private Decodificadores() {}

    /**
     * Chaves do endereço JWKS do Keycloak, baixadas no primeiro pedido e guardadas em cache: o
     * serviço sobe mesmo com o Keycloak fora do ar.
     */
    public static NimbusJwtDecoder doKeycloak(String jwks, String emissor, String audiencia) {
        return validar(NimbusJwtDecoder.withJwkSetUri(jwks).build(), emissor, audiencia);
    }

    /**
     * Validade ({@code exp}, {@code nbf}, com 60 s de tolerância de relógio), emissor ({@code iss})
     * e audiência ({@code aud}). Sem a audiência, um token emitido para outro sistema do mesmo
     * Keycloak passaria aqui.
     */
    public static NimbusJwtDecoder validar(NimbusJwtDecoder decodificador, String emissor, String audiencia) {
        OAuth2TokenValidator<Jwt> audienciaCerta = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD, aud -> aud != null && aud.contains(audiencia));
        decodificador.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(emissor), audienciaCerta));
        return decodificador;
    }
}
