package io.github.alanjoffre.ledgerly.contas.adaptadores.saida.persistencia;

import io.github.alanjoffre.ledgerly.contas.dominio.Cliente;
import io.github.alanjoffre.ledgerly.contas.dominio.Cpf;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA (o formato da tabela), separada do {@link Cliente} do domínio (o formato do negócio).
 * Assim o domínio não carrega anotações de banco, e uma mudança de tabela não vaza para as regras.
 */
@Entity
@Table(name = "cliente")
class ClienteEntidade {

    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String nome;

    @Column(nullable = false, length = 11)
    private String cpf;

    @Column(name = "cadastrado_em", nullable = false)
    private Instant cadastradoEm;

    /** Exigido pelo JPA. */
    protected ClienteEntidade() {}

    static ClienteEntidade de(Cliente cliente) {
        ClienteEntidade e = new ClienteEntidade();
        e.id = cliente.id();
        e.nome = cliente.nome();
        e.cpf = cliente.cpf().numero();
        e.cadastradoEm = cliente.cadastradoEm();
        return e;
    }

    Cliente paraDominio() {
        return new Cliente(id, nome, new Cpf(cpf), cadastradoEm);
    }
}
