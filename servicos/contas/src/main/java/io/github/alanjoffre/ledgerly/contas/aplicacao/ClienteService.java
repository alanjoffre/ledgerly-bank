package io.github.alanjoffre.ledgerly.contas.aplicacao;

import io.github.alanjoffre.ledgerly.contas.aplicacao.porta.ClienteRepository;
import io.github.alanjoffre.ledgerly.contas.dominio.Cliente;
import io.github.alanjoffre.ledgerly.contas.dominio.ClienteNaoEncontradoException;
import io.github.alanjoffre.ledgerly.contas.dominio.Cpf;
import io.github.alanjoffre.ledgerly.contas.dominio.CpfJaCadastradoException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Casos de uso de cliente. */
@Service
public class ClienteService {

    private final ClienteRepository clientes;
    private final Clock relogio;

    public ClienteService(ClienteRepository clientes, Clock relogio) {
        this.clientes = clientes;
        this.relogio = relogio;
    }

    /**
     * Cadastra o cliente. A checagem de CPF repetido aqui dá uma mensagem clara no caso comum; a
     * restrição UNIQUE do banco garante o caso raro de dois cadastros simultâneos.
     */
    @Transactional
    public Cliente cadastrar(String nome, Cpf cpf) {
        if (clientes.existeComCpf(cpf)) {
            throw new CpfJaCadastradoException("Já existe cliente com o CPF " + cpf.mascarado());
        }
        Cliente cliente = Cliente.cadastrar(nome, cpf, relogio.instant());
        clientes.inserir(cliente);
        return cliente;
    }

    @Transactional(readOnly = true)
    public Cliente consultar(UUID id) {
        return clientes.buscar(id)
                .orElseThrow(() -> new ClienteNaoEncontradoException("Cliente %s não encontrado".formatted(id)));
    }
}
