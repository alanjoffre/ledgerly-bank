/**
 * Domínio do razão contábil: as regras de negócio, em Java puro.
 *
 * <p>Nada aqui conhece Spring, banco de dados ou HTTP — por isso as regras podem ser
 * testadas em milissegundos, sem subir nada. O teste {@code ArquiteturaTest} garante.
 *
 * <p>Conceitos (ver {@code docs/glossario.md}):
 * <ul>
 *   <li>{@link io.github.alanjoffre.ledgerly.razao.dominio.ContaRazao} — conta com saldo e natureza;</li>
 *   <li>{@link io.github.alanjoffre.ledgerly.razao.dominio.Lancamento} — um fato contábil, com duas ou
 *       mais {@link io.github.alanjoffre.ledgerly.razao.dominio.Partida partidas};</li>
 *   <li>{@link io.github.alanjoffre.ledgerly.razao.dominio.Escrituracao} — aplica um lançamento às contas.</li>
 * </ul>
 */
package io.github.alanjoffre.ledgerly.razao.dominio;
