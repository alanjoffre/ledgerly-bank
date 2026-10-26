package io.github.alanjoffre.ledgerly.contas.adaptadores.saida.persistencia;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ContaJpa extends JpaRepository<ContaEntidade, UUID> {

    List<ContaEntidade> findByClienteIdOrderByNumero(UUID clienteId);

    @Query(value = "SELECT nextval('numero_conta_seq')", nativeQuery = true)
    long proximoNumero();
}
