package io.github.alanjoffre.ledgerly.contas.aplicacao.porta;

import io.github.alanjoffre.ledgerly.contas.dominio.Cliente;
import io.github.alanjoffre.ledgerly.contas.dominio.Cpf;
import java.util.Optional;
import java.util.UUID;

/** Porta de saída: clientes. */
public interface ClienteRepository {

    /**
     * @throws io.github.alanjoffre.ledgerly.contas.dominio.CpfJaCadastradoException se o CPF já existir
     *     (inclusive numa corrida entre dois cadastros simultâneos)
     */
    void inserir(Cliente cliente);

    Optional<Cliente> buscar(UUID id);

    boolean existeComCpf(Cpf cpf);
}
