package io.github.alanjoffre.ledgerly.seguranca;

import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * O pedido autenticado: o principal é o {@link Usuario} (o controller recebe com
 * {@code @AuthenticationPrincipal Usuario}); a credencial é o token.
 */
public final class AutenticacaoLedgerly extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final transient Usuario usuario;
    private final transient Jwt token;

    AutenticacaoLedgerly(Usuario usuario, Jwt token, Collection<? extends GrantedAuthority> autoridades) {
        super(autoridades);
        this.usuario = usuario;
        this.token = token;
        setAuthenticated(true);
    }

    @Override
    public Usuario getPrincipal() {
        return usuario;
    }

    @Override
    public Jwt getCredentials() {
        return token;
    }

    @Override
    public String getName() {
        return usuario.id();
    }
}
