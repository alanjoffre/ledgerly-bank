package io.github.alanjoffre.ledgerly.razao.adaptadores.saida.persistencia;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import io.github.alanjoffre.ledgerly.razao.aplicacao.porta.ContaRazaoRepository;
import io.github.alanjoffre.ledgerly.razao.dominio.ContaRazao;
import io.github.alanjoffre.ledgerly.razao.dominio.Natureza;
import io.github.alanjoffre.ledgerly.razao.dominio.SituacaoConta;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Adaptador de saída: contas do razão no PostgreSQL, com SQL explícito (ADR-0008). */
@Repository
class JdbcContaRazaoRepository implements ContaRazaoRepository {

    private static final String COLUNAS = "id, natureza, moeda, saldo, permite_saldo_negativo, situacao";

    private final JdbcClient jdbc;

    JdbcContaRazaoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@code ORDER BY id FOR UPDATE}: o PostgreSQL trava as linhas na ordem em que as devolve,
     * então todas as transações travam as contas na mesma ordem — e não há deadlock.
     * Quem chegar depois espera aqui até a transação anterior terminar.
     */
    @Override
    public Map<UUID, ContaRazao> travarParaAtualizacao(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ContaRazao> contas = new LinkedHashMap<>();
        jdbc.sql("SELECT " + COLUNAS + " FROM conta_razao WHERE id IN (:ids) ORDER BY id FOR UPDATE")
                .param("ids", new TreeSet<>(ids))
                .query(JdbcContaRazaoRepository::paraConta)
                .list()
                .forEach(conta -> contas.put(conta.id(), conta));
        return contas;
    }

    @Override
    public Optional<ContaRazao> buscar(UUID id) {
        return jdbc.sql("SELECT " + COLUNAS + " FROM conta_razao WHERE id = :id")
                .param("id", id)
                .query(JdbcContaRazaoRepository::paraConta)
                .optional();
    }

    /** {@code ON CONFLICT DO NOTHING}: dois pedidos simultâneos de abertura não geram erro, só uma conta. */
    @Override
    public boolean inserirSeAusente(ContaRazao conta) {
        int inseridas = jdbc.sql("""
                        INSERT INTO conta_razao (id, natureza, moeda, saldo, permite_saldo_negativo, situacao)
                        VALUES (:id, :natureza, :moeda, :saldo, :permiteSaldoNegativo, :situacao)
                        ON CONFLICT (id) DO NOTHING
                        """)
                .param("id", conta.id())
                .param("natureza", conta.natureza().name())
                .param("moeda", conta.moeda().getCurrencyCode())
                .param("saldo", conta.saldo().valor())
                .param("permiteSaldoNegativo", conta.permiteSaldoNegativo())
                .param("situacao", conta.situacao().name())
                .update();
        return inseridas == 1;
    }

    @Override
    public void atualizar(Collection<ContaRazao> contas) {
        for (ContaRazao conta : contas) {
            jdbc.sql("UPDATE conta_razao SET saldo = :saldo, situacao = :situacao WHERE id = :id")
                    .param("saldo", conta.saldo().valor())
                    .param("situacao", conta.situacao().name())
                    .param("id", conta.id())
                    .update();
        }
    }

    private static ContaRazao paraConta(ResultSet rs, int linha) throws SQLException {
        Currency moeda = Currency.getInstance(rs.getString("moeda"));
        return ContaRazao.reconstituir(
                rs.getObject("id", UUID.class),
                Natureza.valueOf(rs.getString("natureza")),
                moeda,
                rs.getBoolean("permite_saldo_negativo"),
                Dinheiro.de(rs.getBigDecimal("saldo"), moeda),
                SituacaoConta.valueOf(rs.getString("situacao")));
    }
}
