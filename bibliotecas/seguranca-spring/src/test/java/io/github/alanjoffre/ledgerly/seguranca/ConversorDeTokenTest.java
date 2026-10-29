package io.github.alanjoffre.ledgerly.seguranca;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

@DisplayName("Conversor de token: realm_access.roles vira ROLE_<papel>")
class ConversorDeTokenTest {

    private final ConversorDeToken conversor = new ConversorDeToken();

    private static Jwt.Builder token() {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("sub-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
    }

    @Test
    @DisplayName("lê papéis, nome e cliente_id")
    void completo() {
        UUID cliente = UUID.randomUUID();
        var jwt = token().claim("preferred_username", "ana")
                .claim("realm_access", Map.of("roles", List.of("cliente", "offline_access")))
                .claim("cliente_id", cliente.toString()).build();

        var autenticacao = (AutenticacaoLedgerly) conversor.convert(jwt);

        assertThat(autenticacao.isAuthenticated()).isTrue();
        assertThat(autenticacao.getName()).isEqualTo("sub-1");
        assertThat(autenticacao.getCredentials()).isSameAs(jwt);
        assertThat(autenticacao.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_cliente", "ROLE_offline_access");
        var usuario = autenticacao.getPrincipal();
        assertThat(usuario.nome()).isEqualTo("ana");
        assertThat(usuario.clienteId()).contains(cliente);
        assertThat(usuario.ehDono(cliente)).isTrue();
    }

    @Test
    @DisplayName("sem realm_access, sem nome e sem cliente_id: sem papéis, nome = sub")
    void vazio() {
        var usuario = (Usuario) conversor.convert(token().claim("x", "y").build()).getPrincipal();
        assertThat(usuario.papeis()).isEmpty();
        assertThat(usuario.nome()).isEqualTo("sub-1");
        assertThat(usuario.clienteId()).isEmpty();
    }

    @Test
    @DisplayName("realm_access sem a lista roles: sem papéis")
    void semLista() {
        var usuario = (Usuario) conversor.convert(token().claim("realm_access", Map.of("x", 1)).build()).getPrincipal();
        assertThat(usuario.papeis()).isEmpty();
    }

    @Test
    @DisplayName("cliente_id que não é UUID: tratado como ausente (sem dono de nada, sem erro 500)")
    void clienteIdInvalido() {
        var usuario = (Usuario) conversor.convert(token().claim("cliente_id", "../outro").build()).getPrincipal();
        assertThat(usuario.clienteId()).isEmpty();
    }
}
