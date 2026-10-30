package io.github.alanjoffre.ledgerly.pix.aplicacao.porta;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import io.github.alanjoffre.ledgerly.pix.dominio.ResultadoDaAvaliacao;
import java.time.Instant;
import java.util.UUID;

/**
 * Porta de saída: o serviço antifraude, que dá a nota de risco do Pix antes do débito (ADR-0033).
 *
 * <p>Como o {@link Razao}, nunca lança exceção por falha do outro lado: fora do ar, lento ou com
 * resposta estranha vira {@link ResultadoDaAvaliacao.SemResposta}, e a transferência decide pela
 * falha segura.
 */
public interface Antifraude {

    /**
     * @param instante quando o Pix foi pedido (a criação da transferência: a mesma em toda repetição)
     * @param contaRecebedoraAbertaEm quando a conta que vai receber foi aberta
     */
    record Pedido(
            UUID transferenciaId,
            UUID pagadorContaId,
            UUID recebedorContaId,
            Dinheiro valor,
            Instant instante,
            Instant contaRecebedoraAbertaEm) {}

    ResultadoDaAvaliacao avaliar(Pedido pedido);
}
