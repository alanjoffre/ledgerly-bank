package io.github.alanjoffre.ledgerly.assistente.dominio;

import java.util.UUID;

/** Uma conta do cliente. O id fica do lado de cá: o modelo de linguagem só vê agência e número. */
public record Conta(UUID id, String agencia, String numero) {}
