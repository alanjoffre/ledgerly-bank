package io.github.alanjoffre.ledgerly.razao.aplicacao.porta;

import io.github.alanjoffre.ledgerly.razao.dominio.Lancamento;
import java.util.List;

/**
 * Porta de saída: a caixa de saída de eventos (<i>Outbox</i>, ADR-0013).
 *
 * <p>Quem chama está dentro da transação do lançamento: o evento é gravado junto com ele, no mesmo
 * banco, e um publicador separado o manda ao Kafka depois. Ou os dois ficam gravados, ou nenhum.
 */
public interface CaixaDeSaida {

    /**
     * Registra o evento {@code LancamentoRegistrado}.
     *
     * @param sequencias o número de cada partida no razão, na ordem de {@link Lancamento#partidas()}
     */
    void lancamentoRegistrado(Lancamento lancamento, List<Long> sequencias);
}
