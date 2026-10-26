package io.github.alanjoffre.ledgerly.razao.adaptadores.saida.persistencia;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import io.github.alanjoffre.ledgerly.razao.aplicacao.ItemExtrato;
import io.github.alanjoffre.ledgerly.razao.aplicacao.porta.LancamentoRepository;
import io.github.alanjoffre.ledgerly.razao.dominio.ChaveIdempotencia;
import io.github.alanjoffre.ledgerly.razao.dominio.ChaveIdempotenciaReutilizadaException;
import io.github.alanjoffre.ledgerly.razao.dominio.Lancamento;
import io.github.alanjoffre.ledgerly.razao.dominio.Partida;
import io.github.alanjoffre.ledgerly.razao.dominio.TipoOperacao;
import io.github.alanjoffre.ledgerly.razao.dominio.TipoPartida;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Adaptador de saída: lançamentos e partidas no PostgreSQL. Só INSERT e SELECT. */
@Repository
class JdbcLancamentoRepository implements LancamentoRepository {

    private final JdbcClient jdbc;

    JdbcLancamentoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Lancamento> buscarPorChave(ChaveIdempotencia chave) {
        return jdbc.sql("SELECT id FROM lancamento WHERE chave_idempotencia = :chave")
                .param("chave", chave.valor())
                .query(UUID.class)
                .optional()
                .flatMap(this::buscarPorId);
    }

    @Override
    public Optional<Lancamento> buscarPorId(UUID id) {
        record Cabecalho(String chave, String tipo, String descricao, Timestamp registradoEm) {}

        return jdbc.sql("SELECT chave_idempotencia, tipo, descricao, registrado_em FROM lancamento WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new Cabecalho(
                        rs.getString("chave_idempotencia"),
                        rs.getString("tipo"),
                        rs.getString("descricao"),
                        rs.getTimestamp("registrado_em")))
                .optional()
                .map(c -> new Lancamento(
                        id,
                        new ChaveIdempotencia(c.chave()),
                        TipoOperacao.valueOf(c.tipo()),
                        c.descricao(),
                        partidasDo(id),
                        c.registradoEm().toInstant()));
    }

    @Override
    public void inserir(Lancamento lancamento) {
        try {
            jdbc.sql("""
                            INSERT INTO lancamento (id, chave_idempotencia, tipo, descricao, registrado_em)
                            VALUES (:id, :chave, :tipo, :descricao, :registradoEm)
                            """)
                    .param("id", lancamento.id())
                    .param("chave", lancamento.chave().valor())
                    .param("tipo", lancamento.tipo().name())
                    .param("descricao", lancamento.descricao())
                    .param("registradoEm", Timestamp.from(lancamento.registradoEm()))
                    .update();
        } catch (DuplicateKeyException e) {
            // Só acontece numa corrida entre dois pedidos DIFERENTES com a mesma chave (pedidos iguais
            // travam as mesmas contas e ficam em fila). A transação é desfeita inteira.
            throw new ChaveIdempotenciaReutilizadaException(
                    "A chave de idempotência '%s' já foi usada para outro pedido".formatted(lancamento.chave()));
        }
        for (Partida partida : lancamento.partidas()) {
            jdbc.sql("""
                            INSERT INTO partida (lancamento_id, conta_id, tipo, valor, saldo_apos)
                            VALUES (:lancamentoId, :contaId, :tipo, :valor, :saldoApos)
                            """)
                    .param("lancamentoId", lancamento.id())
                    .param("contaId", partida.contaId())
                    .param("tipo", partida.tipo().name())
                    .param("valor", partida.valor().valor())
                    .param("saldoApos", partida.saldoApos().valor())
                    .update();
        }
    }

    @Override
    public List<ItemExtrato> extrato(UUID contaId, int limite) {
        return jdbc.sql("""
                        SELECT p.lancamento_id, l.tipo AS operacao, l.descricao, p.tipo, p.valor, p.saldo_apos,
                               l.registrado_em, c.moeda
                          FROM partida p
                          JOIN lancamento l ON l.id = p.lancamento_id
                          JOIN conta_razao c ON c.id = p.conta_id
                         WHERE p.conta_id = :contaId
                         ORDER BY p.id DESC
                         LIMIT :limite
                        """)
                .param("contaId", contaId)
                .param("limite", limite)
                .query((rs, n) -> {
                    Currency moeda = Currency.getInstance(rs.getString("moeda"));
                    return new ItemExtrato(
                            rs.getObject("lancamento_id", UUID.class),
                            TipoOperacao.valueOf(rs.getString("operacao")),
                            rs.getString("descricao"),
                            TipoPartida.valueOf(rs.getString("tipo")),
                            Dinheiro.de(rs.getBigDecimal("valor"), moeda),
                            Dinheiro.de(rs.getBigDecimal("saldo_apos"), moeda),
                            rs.getTimestamp("registrado_em").toInstant());
                })
                .list();
    }

    private List<Partida> partidasDo(UUID lancamentoId) {
        return jdbc.sql("""
                        SELECT p.conta_id, p.tipo, p.valor, p.saldo_apos, c.moeda
                          FROM partida p
                          JOIN conta_razao c ON c.id = p.conta_id
                         WHERE p.lancamento_id = :id
                         ORDER BY p.id
                        """)
                .param("id", lancamentoId)
                .query(JdbcLancamentoRepository::paraPartida)
                .list();
    }

    private static Partida paraPartida(ResultSet rs, int linha) throws SQLException {
        Currency moeda = Currency.getInstance(rs.getString("moeda"));
        return new Partida(
                rs.getObject("conta_id", UUID.class),
                TipoPartida.valueOf(rs.getString("tipo")),
                Dinheiro.de(rs.getBigDecimal("valor"), moeda),
                Dinheiro.de(rs.getBigDecimal("saldo_apos"), moeda));
    }
}
