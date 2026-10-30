package io.github.alanjoffre.ledgerly.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

@DisplayName("Papéis e cliente_id lidos do token de acesso")
class DoTokenTest {

    @Test
    @DisplayName("lê os papéis do realm e o cliente_id")
    void le() {
        UUID cliente = UUID.randomUUID();
        var token = DoToken.ler(Sessoes.tokenDeAcesso("ana", cliente, "cliente", "offline_access"));
        assertThat(token.papeis()).containsExactlyInAnyOrder("cliente", "offline_access");
        assertThat(token.clienteId()).contains(cliente);
    }

    @Test
    @DisplayName("sem realm_access, sem papéis; cliente_id que não é UUID conta como ausente")
    void semPapeis() {
        var claims = new JWTClaimsSet.Builder().claim("cliente_id", "nao-e-uuid").claim("realm_access", Map.of()).build();
        var token = DoToken.ler(new PlainJWT(claims).serialize());
        assertThat(token.papeis()).isEmpty();
        assertThat(token.clienteId()).isEmpty();
    }

    @Test
    @DisplayName("um token opaco (não JWT) não dá papel nenhum")
    void opaco() {
        var token = DoToken.ler("isto-nao-e-um-jwt");
        assertThat(token.papeis()).isEmpty();
        assertThat(token.clienteId()).isEmpty();
    }

    @Test
    @DisplayName("a pessoa logada ganha ROLE_<papel> e o cliente_id, sem perder o que já tinha")
    void usuario() {
        UUID cliente = UUID.randomUUID();
        var bff = new PropriedadesDoBff("cliente", "http://localhost:18081/realms/ledgerly", "ledgerly-internet-banking", "s",
                new PropriedadesDoBff.Servicos(URI.create("http://c"), URI.create("http://e"), URI.create("http://p"), URI.create("http://a"), URI.create("http://s")), "file:dist/");
        var registro = new ConfiguracaoDeLogin().registros(bff).findByRegistrationId(ConfiguracaoDeLogin.REGISTRO);
        var tokenDeAcesso = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                Sessoes.tokenDeAcesso("ana", cliente, "cliente"), Instant.now(), Instant.now().plusSeconds(60));
        var pedido = new OidcUserRequest(registro, tokenDeAcesso, Sessoes.idToken("ana"));

        var usuario = new ConfiguracaoDeLogin().usuarios().loadUser(pedido);

        assertThat(usuario.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_cliente", "OIDC_USER");
        assertThat(usuario.getPreferredUsername()).isEqualTo("ana");
        assertThat(usuario.getName()).isEqualTo("ana");
        assertThat(usuario.<String>getClaim("cliente_id")).isEqualTo(cliente.toString());
    }

    @Test
    @DisplayName("subir sem perfil falha logo, dizendo o que falta")
    void semPerfil() {
        assertThatThrownBy(() -> new PropriedadesDoBff(null, "e", "c", "s", null, null))
                .hasMessageContaining("internet-banking ou backoffice");
    }
}
