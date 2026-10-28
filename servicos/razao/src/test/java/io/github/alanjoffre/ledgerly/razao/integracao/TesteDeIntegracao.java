package io.github.alanjoffre.ledgerly.razao.integracao;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import io.github.alanjoffre.ledgerly.razao.aplicacao.Comandos;
import io.github.alanjoffre.ledgerly.razao.aplicacao.ContaRazaoService;
import io.github.alanjoffre.ledgerly.razao.aplicacao.LancamentoService;
import io.github.alanjoffre.ledgerly.razao.dominio.ChaveIdempotencia;
import io.github.alanjoffre.ledgerly.razao.dominio.ContaRazao;
import io.github.alanjoffre.ledgerly.razao.dominio.ContasQuentes;
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
        // As contas internas (e as suas sub-contas, ADR-0019) têm os 64 bits altos zerados.
        jdbc.sql("DELETE FROM conta_razao WHERE id::text NOT LIKE '00000000-0000-0000-0000-%'").update();
        jdbc.sql("UPDATE conta_razao SET saldo = 0 WHERE id::text LIKE '00000000-0000-0000-0000-%'").update();
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

    /** O saldo da conta; numa conta interna (Caixa, Liquidação Pix), a soma das sub-contas. */
    protected Dinheiro saldo(UUID conta) {
        if (!ContaRazao.ehInterna(conta)) {
            return contas.consultar(conta).saldo();
        }
        var soma = jdbc.sql("SELECT COALESCE(SUM(saldo), 0) FROM conta_razao WHERE id IN (:ids)")
                .param("ids", ContasQuentes.todasAsSubcontas(conta))
                .query(java.math.BigDecimal.class)
                .single();
        return Dinheiro.de(soma, Dinheiro.REAL);
    }

    protected static ChaveIdempotencia chave() {
        return new ChaveIdempotencia(UUID.randomUUID().toString());
    }
}
