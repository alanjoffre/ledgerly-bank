package io.github.alanjoffre.ledgerly.gateway;

import com.nimbusds.jwt.JWTParser;
import java.text.ParseException;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Os papéis e o {@code cliente_id} lidos do token de acesso, que o gateway recebeu do Keycloak
 * na troca do código (canal direto, servidor a servidor).
 *
 * <p>Por que do token de acesso e não do ID token: no Keycloak, os papéis do realm vão no token de
 * acesso ({@code realm_access.roles}), e é ele que os serviços conferem. Aqui não se confere a
 * assinatura — o token veio do próprio Keycloak, na resposta à troca feita pelo gateway; quem
 * confere a assinatura em toda chamada são os serviços (ADR-0025).
 */
record DoToken(Set<String> papeis, Optional<UUID> clienteId) {

    static DoToken ler(String tokenDeAcesso) {
        try {
            Map<String, Object> claims = JWTParser.parse(tokenDeAcesso).getJWTClaimsSet().getClaims();
            Set<String> papeis = Set.of();
            if (claims.get("realm_access") instanceof Map<?, ?> acesso && acesso.get("roles") instanceof Collection<?> lista) {
                papeis = lista.stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
            }
            return new DoToken(papeis, uuid(claims.get("cliente_id")));
        } catch (ParseException e) {
            return new DoToken(Set.of(), Optional.empty());
        }
    }

    private static Optional<UUID> uuid(Object valor) {
        if (valor == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(valor.toString()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
