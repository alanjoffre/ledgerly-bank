package io.github.alanjoffre.ledgerly.contas.aplicacao.porta;

import io.github.alanjoffre.ledgerly.contas.dominio.Conta;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Porta de saída: contas. */
public interface ContaRepository {

    /** Próximo número de conta, de uma sequência do banco de dados (nunca se repete). */
    int proximoNumero();

    void inserir(Conta conta);

    Optional<Conta> buscar(UUID id);

    List<Conta> listarDoCliente(UUID clienteId);

    /**
     * Grava a nova situação. Usa trava otimista: se outra transação alterou a mesma conta depois
     * que esta a leu, lança {@link io.github.alanjoffre.ledgerly.contas.aplicacao.ConflitoDeAtualizacaoException}.
     */
    void atualizar(Conta conta);
}
