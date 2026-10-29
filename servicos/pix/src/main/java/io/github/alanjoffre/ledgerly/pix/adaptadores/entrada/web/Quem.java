package io.github.alanjoffre.ledgerly.pix.adaptadores.entrada.web;

import io.github.alanjoffre.ledgerly.pix.aplicacao.Acesso;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Quem fez o pedido, lido do token já conferido pelo quarkus-oidc — o equivalente do
 * {@code Usuario} dos serviços Spring. Daqui sai o {@link Acesso} que a aplicação usa.
 */
@RequestScoped
class Quem {

    static final String CLIENTE = "cliente";
    static final String ANALISTA = "analista";
    static final String SERVICO = "servico";

    private final SecurityIdentity identidade;
    private final JsonWebToken token;

    Quem(SecurityIdentity identidade, JsonWebToken token) {
        this.identidade = identidade;
        this.token = token;
    }

    /** Analista e serviço não têm dono a conferir; o cliente só usa o que é dele. */
    Acesso acesso() {
        if (identidade.hasRole(ANALISTA) || identidade.hasRole(SERVICO)) {
            return Acesso.TOTAL;
        }
        Optional<UUID> clienteId = identidade.hasRole(CLIENTE) ? clienteId() : Optional.empty();
        return dono -> clienteId.filter(dono::equals).isPresent();
    }

    /** O {@code sub}: o id da pessoa no Keycloak. */
    String id() {
        return token.getSubject();
    }

    String nome() {
        String nome = token.getClaim("preferred_username");
        return nome == null ? id() : nome;
    }

    /** O claim {@code cliente_id}; um valor que não é UUID conta como ausente (nunca dono). */
    private Optional<UUID> clienteId() {
        Object valor = token.getClaim("cliente_id");
        if (valor == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(valor.toString().replace("\"", "")));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
