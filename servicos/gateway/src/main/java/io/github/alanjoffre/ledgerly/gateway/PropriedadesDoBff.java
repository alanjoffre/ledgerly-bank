package io.github.alanjoffre.ledgerly.gateway;

import java.net.URI;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * A configuração de cada BFF ({@code ledgerly.bff.*} no application.yaml, por perfil).
 *
 * @param papel          o papel do realm que este front-end exige ({@code cliente} ou {@code analista})
 * @param emissor        o realm do Keycloak, como aparece no {@code iss} do token
 * @param clienteId      o cliente OIDC deste front-end no Keycloak
 * @param clienteSegredo o segredo dele (local; em nuvem, de um cofre)
 * @param servicos       para onde vão as rotas
 * @param pagina         onde está a tela construída ({@code npm run construir}), servida pelo gateway
 */
@ConfigurationProperties("ledgerly.bff")
public record PropriedadesDoBff(String papel, String emissor, String clienteId, String clienteSegredo, Servicos servicos, String pagina) {

    public PropriedadesDoBff {
        Objects.requireNonNull(papel, "Suba o gateway com um perfil: internet-banking ou backoffice");
        Objects.requireNonNull(emissor, "ledgerly.bff.emissor é obrigatório");
        Objects.requireNonNull(clienteId, "ledgerly.bff.cliente-id é obrigatório");
        Objects.requireNonNull(servicos, "ledgerly.bff.servicos é obrigatório");
    }

    public record Servicos(URI contas, URI extrato, URI pix, URI antifraude, URI assistente) {}
}
