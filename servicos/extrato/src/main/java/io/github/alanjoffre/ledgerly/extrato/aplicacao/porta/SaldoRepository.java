package io.github.alanjoffre.ledgerly.extrato.aplicacao.porta;

import io.github.alanjoffre.ledgerly.extrato.dominio.Saldo;
import java.util.Optional;
import java.util.UUID;

/** Porta de saída: o saldo de cada conta no modelo de leitura. */
public interface SaldoRepository {

    /**
     * Grava o saldo só se ele for mais novo que o guardado ({@link Saldo#ehMaisNovoQue(Saldo)}),
     * numa operação atômica — dois consumidores ao mesmo tempo não voltam o saldo para trás.
     *
     * @return {@code true} se gravou; {@code false} se o guardado já era igual ou mais novo
     */
    boolean atualizarSeMaisNovo(Saldo saldo);

    Optional<Saldo> buscar(UUID contaId);
}
