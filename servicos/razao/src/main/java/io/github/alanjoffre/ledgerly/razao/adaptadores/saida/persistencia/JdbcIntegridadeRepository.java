package io.github.alanjoffre.ledgerly.razao.adaptadores.saida.persistencia;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import io.github.alanjoffre.ledgerly.razao.aplicacao.RelatorioIntegridade;
import io.github.alanjoffre.ledgerly.razao.aplicacao.RelatorioIntegridade.BalancoPorMoeda;
import io.github.alanjoffre.ledgerly.razao.aplicacao.porta.IntegridadeRepository;
import java.util.Currency;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * As três conferências do razão, em SQL. Cada uma recalcula a partir das partidas — a fonte da
 * verdade — e compara com o que está gravado.
 */
@Repository
class JdbcIntegridadeRepository implements IntegridadeRepository {

    private final JdbcClient jdbc;

    JdbcIntegridadeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RelatorioIntegridade verificar() {
        // 1. Balanço: por moeda, total das devedoras x total das credoras.
        var balancos = jdbc.sql("""
                        SELECT moeda,
                               COALESCE(SUM(saldo) FILTER (WHERE natureza = 'DEVEDORA'), 0) AS devedoras,
                               COALESCE(SUM(saldo) FILTER (WHERE natureza = 'CREDORA'), 0)  AS credoras
                          FROM conta_razao
                         GROUP BY moeda
                         ORDER BY moeda
                        """)
                .query((rs, n) -> {
                    Currency moeda = Currency.getInstance(rs.getString("moeda"));
                    return new BalancoPorMoeda(
                            Dinheiro.de(rs.getBigDecimal("devedoras"), moeda),
                            Dinheiro.de(rs.getBigDecimal("credoras"), moeda));
                })
                .list();

        // 2. Saldo gravado x soma das partidas (com o sinal dado pela natureza da conta).
        var divergentes = jdbc.sql("""
                        SELECT c.id
                          FROM conta_razao c
                          LEFT JOIN partida p ON p.conta_id = c.id
                         GROUP BY c.id, c.natureza, c.saldo
                        HAVING c.saldo <> COALESCE(SUM(
                                   CASE WHEN (p.tipo = 'DEBITO') = (c.natureza = 'DEVEDORA') THEN p.valor
                                        ELSE -p.valor END), 0)
                         ORDER BY c.id
                        """)
                .query(UUID.class)
                .list();

        // 3. Lançamentos com débitos diferentes de créditos.
        var desbalanceados = jdbc.sql("""
                        SELECT lancamento_id
                          FROM partida
                         GROUP BY lancamento_id
                        HAVING SUM(CASE tipo WHEN 'DEBITO' THEN valor ELSE -valor END) <> 0
                         ORDER BY lancamento_id
                        """)
                .query(UUID.class)
                .list();

        return new RelatorioIntegridade(balancos, divergentes, desbalanceados);
    }
}
