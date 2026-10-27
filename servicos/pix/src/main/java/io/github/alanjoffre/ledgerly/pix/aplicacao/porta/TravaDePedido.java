package io.github.alanjoffre.ledgerly.pix.aplicacao.porta;

import java.time.Duration;

/**
 * Porta de saída: a trava rápida contra pedido repetido enquanto o primeiro ainda está em
 * andamento (no Redis, ADR-0018).
 *
 * <p>É uma camada <b>antes</b> do banco, não no lugar dele: quem garante "uma chave, uma
 * transferência" é o {@code UNIQUE} do PostgreSQL. Se o Redis estiver fora do ar, a trava deixa
 * passar, e o banco segura sozinho.
 */
public interface TravaDePedido {

    /** @return {@code true} se travou agora; {@code false} se outro pedido com a mesma chave está em andamento */
    boolean travar(String chave, Duration validade);

    void liberar(String chave);
}
