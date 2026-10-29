package io.github.alanjoffre.ledgerly.seguranca;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Do token (já conferido) para a autenticação do Spring.
 *
 * <p>O Keycloak põe os papéis do realm em {@code realm_access.roles} — um caminho aninhado que o
 * conversor padrão do Spring (que lê {@code scope}) não conhece. Cada papel vira a autoridade
 * {@code ROLE_<papel>}, e as regras usam {@code hasRole("cliente")}.
 *
 * <p>Um {@code cliente_id} que não é UUID é tratado como ausente: a pessoa fica sem dono de nada,
 * em vez de derrubar o pedido com erro 500.
 */
public final class ConversorDeToken implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt token) {
        Set<String> papeis = papeis(token);
        var usuario = new Usuario(
                token.getSubject(),
                Optional.ofNullable(token.getClaimAsString("preferred_username")).orElse(token.getSubject()),
                clienteId(token),
                papeis);
        var autoridades = papeis.stream().map(p -> new SimpleGrantedAuthority("ROLE_" + p)).toList();
        return new AutenticacaoLedgerly(usuario, token, autoridades);
    }

    private static Set<String> papeis(Jwt token) {
        Map<String, Object> acesso = token.getClaimAsMap("realm_access");
        if (acesso == null || !(acesso.get("roles") instanceof Collection<?> papeis)) {
            return Set.of();
        }
        return papeis.stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
    }

    private static Optional<UUID> clienteId(Jwt token) {
        String valor = token.getClaimAsString("cliente_id");
        if (valor == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(valor));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
