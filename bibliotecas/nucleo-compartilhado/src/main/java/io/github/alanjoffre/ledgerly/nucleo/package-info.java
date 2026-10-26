/**
 * Núcleo compartilhado (<i>shared kernel</i>, no DDD): os tipos de domínio que todos os
 * serviços do Ledgerly Bank usam do mesmo jeito.
 *
 * <p>Regra: este pacote depende só do próprio Java. Nada de Spring, Quarkus ou banco de
 * dados — o teste {@code ArquiteturaTest} reprova o build se alguém quebrar a regra.
 */
package io.github.alanjoffre.ledgerly.nucleo;
