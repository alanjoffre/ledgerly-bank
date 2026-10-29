package io.github.alanjoffre.ledgerly.seguranca;

/** Os papéis do realm {@code ledgerly} (infra/docker/keycloak/ledgerly-realm.json). */
public final class Papeis {

    /** Cliente do banco: vê e movimenta só as próprias contas. */
    public static final String CLIENTE = "cliente";
    /** Analista do backoffice: fila de análise, devolução e bloqueio de conta. */
    public static final String ANALISTA = "analista";
    /** Outro serviço do Ledgerly, com token de <i>client credentials</i> (sem pessoa). */
    public static final String SERVICO = "servico";

    private Papeis() {}
}
