package io.github.alanjoffre.ledgerly.razao.aplicacao.porta;

import io.github.alanjoffre.ledgerly.razao.dominio.ContaRazao;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Porta de saída: onde as contas do razão são guardadas. */
public interface ContaRazaoRepository {

    /**
     * Trava as contas para atualização até o fim da transação ({@code SELECT ... FOR UPDATE}),
     * <b>sempre em ordem crescente de id</b>, e devolve as que existem.
     *
     * <p>A ordem fixa evita deadlock: se a transação 1 trava A e espera B enquanto a 2 trava B e
     * espera A, nenhuma anda. Travando sempre na mesma ordem, a 2 espera em A e a 1 termina.
     */
    Map<UUID, ContaRazao> travarParaAtualizacao(Collection<UUID> ids);

    Optional<ContaRazao> buscar(UUID id);

    /** Insere a conta se o id ainda não existir. Devolve {@code true} se inseriu. */
    boolean inserirSeAusente(ContaRazao conta);

    /** Grava saldo e situação. */
    void atualizar(Collection<ContaRazao> contas);
}
