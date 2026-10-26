package io.github.alanjoffre.ledgerly.contas.adaptadores.saida.persistencia;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA: só a interface — o Spring gera a implementação. O nome do método vira a
 * consulta: {@code existsByCpf} → {@code SELECT ... WHERE cpf = ?}.
 */
interface ClienteJpa extends JpaRepository<ClienteEntidade, UUID> {

    boolean existsByCpf(String cpf);
}
