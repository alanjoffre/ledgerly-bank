/**
 * Segurança dos serviços Spring: conferir o token do Keycloak e dizer quem chamou (ADR-0024).
 *
 * <p>Dois tipos de chamador, os dois com token JWT assinado pelo Keycloak:
 * <ul>
 *   <li><b>pessoa</b> (cliente ou analista), cujo token chega pelo BFF (ADR-0025);</li>
 *   <li><b>serviço</b> (contas, pix, extrato), que pede o próprio token com o fluxo
 *       <i>client credentials</i> (ADR-0027).</li>
 * </ul>
 * A autorização por papel fica nas regras de cada serviço ({@link SegurancaDeRecurso}); a
 * autorização por objeto ("esta conta é sua?") fica no caso de uso (ADR-0026).
 */
package io.github.alanjoffre.ledgerly.seguranca;
