package io.github.alanjoffre.ledgerly.gateway;

import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/eu}: quem está logado — para a tela mostrar o nome e saber o que pode. Responde
 * a qualquer pessoa logada (mesmo sem o papel deste front-end), para a tela dizer "este acesso não
 * é seu" em vez de só falhar. O token não sai daqui.
 */
@RestController
class EuController {

    record Eu(String nome, List<String> papeis, String clienteId) {}

    @GetMapping("/api/eu")
    Eu eu(@AuthenticationPrincipal OidcUser usuario) {
        List<String> papeis = usuario.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .sorted()
                .toList();
        return new Eu(usuario.getPreferredUsername(), papeis, usuario.getClaimAsString("cliente_id"));
    }
}
