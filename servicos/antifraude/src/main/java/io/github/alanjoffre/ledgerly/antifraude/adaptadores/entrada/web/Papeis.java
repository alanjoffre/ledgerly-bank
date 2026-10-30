package io.github.alanjoffre.ledgerly.antifraude.adaptadores.entrada.web;

/** Os papéis do Keycloak (realm_access.roles) que o antifraude conhece (ADR-0026). */
final class Papeis {

    /** O serviço pix, que pergunta antes de debitar. */
    static final String SERVICO = "servico";
    /** A pessoa do backoffice, que olha por que um Pix foi retido. */
    static final String ANALISTA = "analista";

    private Papeis() {}
}
