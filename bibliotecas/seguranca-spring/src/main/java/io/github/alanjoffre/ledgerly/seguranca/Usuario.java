package io.github.alanjoffre.ledgerly.seguranca;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Quem fez o pedido, lido do token já conferido.
 *
 * @param id        o {@code sub} do token: o id da pessoa (ou do serviço) no Keycloak
 * @param nome      o {@code preferred_username}: "ana", "carla", "service-account-ledgerly-pix"
 * @param clienteId o claim {@code cliente_id}: o id do cliente no serviço de contas. Só a pessoa
 *                  cliente tem; quem grava é a semeadura, pela API de administração do Keycloak — a
 *                  própria pessoa não consegue mudar (ver o user profile do realm, ADR-0024)
 * @param papeis    os papéis do realm ({@code realm_access.roles})
 */
public record Usuario(String id, String nome, Optional<UUID> clienteId, Set<String> papeis) {

    public Usuario {
        papeis = Set.copyOf(papeis);
    }

    public boolean tem(String papel) {
        return papeis.contains(papel);
    }

    public boolean ehServico() {
        return tem(Papeis.SERVICO);
    }

    public boolean ehAnalista() {
        return tem(Papeis.ANALISTA);
    }

    /** O recurso pertence a esta pessoa? Sem o claim {@code cliente_id}, nunca. */
    public boolean ehDono(UUID clienteDoRecurso) {
        return tem(Papeis.CLIENTE) && clienteId.filter(clienteDoRecurso::equals).isPresent();
    }
}
