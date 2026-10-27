/**
 * Camada de aplicação do extrato: os dois lados do CQRS.
 *
 * <ul>
 *   <li>{@link io.github.alanjoffre.ledgerly.extrato.aplicacao.ProjecaoDoExtrato} — a
 *       <b>projeção</b>: transforma eventos em documentos do modelo de leitura;</li>
 *   <li>{@link io.github.alanjoffre.ledgerly.extrato.aplicacao.ConsultaDoExtrato} — a
 *       <b>consulta</b>: lê o modelo pronto, sem recalcular nada.</li>
 * </ul>
 *
 * <p>Fala com o MongoDB só pelas interfaces do pacote {@code porta}. Única dependência de framework
 * permitida: a anotação {@code @Service} do Spring.
 */
package io.github.alanjoffre.ledgerly.extrato.aplicacao;
