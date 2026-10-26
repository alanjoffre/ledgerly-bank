package io.github.alanjoffre.ledgerly.contas.adaptadores.saida.persistencia;

import io.github.alanjoffre.ledgerly.contas.aplicacao.ConflitoDeAtualizacaoException;
import io.github.alanjoffre.ledgerly.contas.aplicacao.porta.ContaRepository;
import io.github.alanjoffre.ledgerly.contas.dominio.Conta;
import io.github.alanjoffre.ledgerly.contas.dominio.ContaNaoEncontradaException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

/** Adaptador de saída: contas via JPA, com trava otimista. */
@Repository
class JpaContaRepository implements ContaRepository {

    private final ContaJpa jpa;
    private final EntityManager entityManager;

    JpaContaRepository(ContaJpa jpa, EntityManager entityManager) {
        this.jpa = jpa;
        this.entityManager = entityManager;
    }

    @Override
    public int proximoNumero() {
        return Math.toIntExact(jpa.proximoNumero());
    }

    @Override
    public void inserir(Conta conta) {
        ContaEntidade entidade = ContaEntidade.nova(conta);
        entidade.definirAbertura(conta.abertaEm());
        entityManager.persist(entidade);
        entityManager.flush();
    }

    @Override
    public Optional<Conta> buscar(UUID id) {
        return jpa.findById(id).map(ContaEntidade::paraDominio);
    }

    @Override
    public List<Conta> listarDoCliente(UUID clienteId) {
        return jpa.findByClienteIdOrderByNumero(clienteId).stream().map(ContaEntidade::paraDominio).toList();
    }

    /**
     * Duas verificações de versão:
     * <ol>
     *   <li>aqui: a versão que o caso de uso leu ainda é a da entidade?</li>
     *   <li>no banco: o {@code UPDATE ... WHERE versao = ?} do Hibernate, que pega a corrida entre
     *       a leitura e a gravação.</li>
     * </ol>
     */
    @Override
    public void atualizar(Conta conta) {
        ContaEntidade entidade = jpa.findById(conta.id())
                .orElseThrow(() -> new ContaNaoEncontradaException("Conta %s não encontrada".formatted(conta.id())));
        if (entidade.versao() != conta.versao()) {
            throw conflito(conta, null);
        }
        entidade.copiarEstado(conta);
        try {
            entityManager.flush();
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException e) {
            throw conflito(conta, e);
        }
    }

    private static ConflitoDeAtualizacaoException conflito(Conta conta, Throwable causa) {
        return new ConflitoDeAtualizacaoException(
                "A conta %s foi alterada por outra operação ao mesmo tempo; leia de novo e repita".formatted(conta.id()),
                causa);
    }
}
