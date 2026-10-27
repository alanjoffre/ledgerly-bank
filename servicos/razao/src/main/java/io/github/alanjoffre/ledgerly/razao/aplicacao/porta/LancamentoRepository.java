package io.github.alanjoffre.ledgerly.razao.aplicacao.porta;

import io.github.alanjoffre.ledgerly.razao.aplicacao.ItemExtrato;
import io.github.alanjoffre.ledgerly.razao.dominio.ChaveIdempotencia;
import io.github.alanjoffre.ledgerly.razao.dominio.Lancamento;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Porta de saída: onde os lançamentos são guardados. Só inserção — o razão é imutável. */
public interface LancamentoRepository {

    Optional<Lancamento> buscarPorChave(ChaveIdempotencia chave);

    Optional<Lancamento> buscarPorId(UUID id);

    /**
     * Grava o lançamento e as partidas.
     *
     * @return a sequência de cada partida (número que só cresce, dado pelo banco), na ordem de
     *     {@link Lancamento#partidas()} — o extrato usa para saber qual saldo é o mais novo
     * @throws io.github.alanjoffre.ledgerly.razao.dominio.ChaveIdempotenciaReutilizadaException
     *     se a chave já existir (corrida entre dois pedidos diferentes com a mesma chave)
     */
    List<Long> inserir(Lancamento lancamento);

    /** As últimas partidas da conta, da mais nova para a mais antiga. */
    List<ItemExtrato> extrato(UUID contaId, int limite);
}
