package io.github.alanjoffre.ledgerly.contas.adaptadores.saida.persistencia;

import io.github.alanjoffre.ledgerly.contas.aplicacao.porta.ClienteRepository;
import io.github.alanjoffre.ledgerly.contas.dominio.Cliente;
import io.github.alanjoffre.ledgerly.contas.dominio.Cpf;
import io.github.alanjoffre.ledgerly.contas.dominio.CpfJaCadastradoException;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Repository;

/** Adaptador de saída: clientes via JPA. */
@Repository
class JpaClienteRepository implements ClienteRepository {

    private final ClienteJpa jpa;
    private final EntityManager entityManager;

    JpaClienteRepository(ClienteJpa jpa, EntityManager entityManager) {
        this.jpa = jpa;
        this.entityManager = entityManager;
    }

    /**
     * {@code persist} + {@code flush}: o INSERT vai ao banco agora, e não no COMMIT — assim a
     * violação da restrição UNIQUE do CPF (cadastro simultâneo) aparece aqui e vira erro de
     * negócio. Qualquer outra falha de banco sobe como está.
     */
    @Override
    public void inserir(Cliente cliente) {
        try {
            entityManager.persist(ClienteEntidade.de(cliente));
            entityManager.flush();
        } catch (ConstraintViolationException e) {
            if ("cpf_unico".equals(e.getConstraintName())) {
                throw new CpfJaCadastradoException("Já existe cliente com o CPF " + cliente.cpf().mascarado());
            }
            throw e;
        }
    }

    @Override
    public Optional<Cliente> buscar(UUID id) {
        return jpa.findById(id).map(ClienteEntidade::paraDominio);
    }

    @Override
    public boolean existeComCpf(Cpf cpf) {
        return jpa.existsByCpf(cpf.numero());
    }
}
