package io.github.alanjoffre.ledgerly.razao.integracao;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import io.github.alanjoffre.ledgerly.razao.aplicacao.Comandos;
import io.github.alanjoffre.ledgerly.razao.aplicacao.ContaRazaoService;
import io.github.alanjoffre.ledgerly.razao.aplicacao.LancamentoService;
import io.github.alanjoffre.ledgerly.razao.dominio.ChaveIdempotencia;
import io.github.alanjoffre.ledgerly.razao.dominio.ContaRazao;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Base dos testes de integração: Spring de verdade + PostgreSQL e Kafka de verdade.
 * Antes de cada teste o razão volta ao estado inicial (só as contas internas, zeradas, e o Outbox vazio).
 */
@SpringBootTest
@Import(ContainersDeTeste.class)
abstract class TesteDeIntegracao {

    private static final List<UUID> INTERNAS = List.of(ContaRazao.CAIXA, ContaRazao.LIQUIDACAO_PIX);

    @Autowired
    protected LancamentoService lancamentos;

    @Autowired
    protected ContaRazaoService contas;

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void limparRazao() {
        // TRUNCATE não dispara os gatilhos de linha que protegem o razão — só serve em teste.
        jdbc.sql("TRUNCATE partida, lancamento, outbox").update();
        jdbc.sql("DELETE FROM conta_razao WHERE id NOT IN (:internas)").param("internas", INTERNAS).update();
        jdbc.sql("UPDATE conta_razao SET saldo = 0 WHERE id IN (:internas)").param("internas", INTERNAS).update();
    }

    protected UUID novaConta() {
        UUID id = UUID.randomUUID();
        contas.abrir(id, Dinheiro.REAL);
        return id;
    }

    protected UUID novaContaCom(String saldo) {
        UUID id = novaConta();
        lancamentos.depositar(new Comandos.Deposito(chave(), id, Dinheiro.reais(saldo), "saldo inicial"));
        return id;
    }

    protected Dinheiro saldo(UUID conta) {
        return contas.consultar(conta).saldo();
    }

    protected static ChaveIdempotencia chave() {
        return new ChaveIdempotencia(UUID.randomUUID().toString());
    }
}
