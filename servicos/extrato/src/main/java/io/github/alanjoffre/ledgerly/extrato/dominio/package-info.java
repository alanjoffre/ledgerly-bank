/**
 * Domínio do extrato: as peças de uma linha do extrato e as regras de como ela aparece para o
 * cliente ("Pix enviado para Maria Souza").
 *
 * <p>Java puro, sem framework: não sabe que os dados vêm do Kafka nem que moram no MongoDB. O
 * extrato não tem regra de dinheiro própria — saldo e valor chegam prontos do razão, que é a fonte
 * da verdade. A regra daqui é de <b>apresentação</b> e de <b>junção</b>: uma linha do extrato é
 * montada com dois pedaços que chegam separados, em qualquer ordem.
 */
package io.github.alanjoffre.ledgerly.extrato.dominio;
