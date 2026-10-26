/**
 * Camada de aplicação: os casos de uso do razão (depositar, sacar, transferir, abrir conta...).
 *
 * <p>Orquestra: trava as contas, chama o domínio, grava pelo repositório. Não tem regra de
 * negócio própria — as regras estão no domínio. Fala com o banco só pelas interfaces do pacote
 * {@code porta} (arquitetura hexagonal: as "portas"; as implementações são os "adaptadores").
 *
 * <p>Única dependência de framework permitida aqui: as anotações {@code @Service} e
 * {@code @Transactional} do Spring (ver {@code docs/adr/0008}).
 */
package io.github.alanjoffre.ledgerly.razao.aplicacao;
