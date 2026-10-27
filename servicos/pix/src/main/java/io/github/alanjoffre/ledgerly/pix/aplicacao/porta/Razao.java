package io.github.alanjoffre.ledgerly.pix.aplicacao.porta;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import io.github.alanjoffre.ledgerly.pix.dominio.Passo;
import io.github.alanjoffre.ledgerly.pix.dominio.ResultadoDoPasso;
import java.util.UUID;

/**
 * Porta de saída: o razão contábil, que é quem move o dinheiro de fato.
 *
 * <p>Nunca lança exceção por falha do razão: toda resposta vira um {@link ResultadoDoPasso}
 * (feito, recusado ou sem resposta), porque a Saga precisa tratar os três casos de jeitos diferentes.
 */
public interface Razao {

    ResultadoDoPasso executar(Passo passo, String chaveDeIdempotencia, UUID contaId, Dinheiro valor, String descricao);
}
