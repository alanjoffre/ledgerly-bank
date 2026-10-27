package io.github.alanjoffre.ledgerly.pix.adaptadores.saida.persistencia;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.TransferenciaRepository;
import io.github.alanjoffre.ledgerly.pix.dominio.ChavePix;
import io.github.alanjoffre.ledgerly.pix.dominio.Participante;
import io.github.alanjoffre.ledgerly.pix.dominio.SituacaoTransferencia;
import io.github.alanjoffre.ledgerly.pix.dominio.TipoChave;
import io.github.alanjoffre.ledgerly.pix.dominio.TransferenciaPix;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Adaptador de saída: as transferências Pix e o estado de cada Saga no PostgreSQL. */
@ApplicationScoped
class JdbcTransferenciaRepository implements TransferenciaRepository {

    /** As situações em que ainda há passo a dar. */
    private static final String EM_ANDAMENTO = "('CRIADA', 'DEBITADA', 'ESTORNANDO')";

    private final DataSource banco;

    JdbcTransferenciaRepository(DataSource banco) {
        this.banco = banco;
    }

    @Override
    public boolean inserir(TransferenciaPix t) {
        var e = t.estado();
        try (Connection c = banco.getConnection();
                PreparedStatement sql = c.prepareStatement("""
                        INSERT INTO transferencia_pix (
                            id, chave_idempotencia, pagador_conta_id, pagador_nome,
                            recebedor_conta_id, recebedor_nome, recebedor_chave, recebedor_tipo_chave,
                            valor, descricao, situacao, tentativas, proxima_tentativa_em, criada_em)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (chave_idempotencia) DO NOTHING
                        """)) {
            sql.setObject(1, e.id());
            sql.setString(2, e.chaveIdempotencia());
            sql.setObject(3, e.pagador().contaId());
            sql.setString(4, e.pagador().nome());
            sql.setObject(5, e.recebedor().contaId());
            sql.setString(6, e.recebedor().nome());
            sql.setString(7, e.chaveDoRecebedor().valor());
            sql.setString(8, e.chaveDoRecebedor().tipo().name());
            sql.setBigDecimal(9, e.valor().valor());
            sql.setString(10, e.descricao());
            sql.setString(11, e.situacao().name());
            sql.setInt(12, e.tentativas());
            Jdbc.instante(sql, 13, e.proximaTentativaEm());
            Jdbc.instante(sql, 14, e.criadaEm());
            // ON CONFLICT DO NOTHING: com a chave repetida, nenhuma linha entra e não há erro.
            return sql.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw Jdbc.erro(ex);
        }
    }

    @Override
    public Optional<TransferenciaPix> buscar(UUID id) {
        return consultar("SELECT * FROM transferencia_pix WHERE id = ?", id);
    }

    @Override
    public Optional<TransferenciaPix> buscarPorChaveDeIdempotencia(String chave) {
        return consultar("SELECT * FROM transferencia_pix WHERE chave_idempotencia = ?", chave);
    }

    @Override
    public Optional<TransferenciaPix> reservar(UUID id, Instant agora, Duration validade) {
        // Um UPDATE só, atômico: confere e reserva de uma vez. Duas execuções ao mesmo tempo — o
        // pedido HTTP e o agendador, ou duas instâncias — não reservam as duas: o PostgreSQL trava
        // a linha no UPDATE, e a segunda, ao reler a condição, já encontra a reserva da primeira.
        try (Connection c = banco.getConnection();
                PreparedStatement sql = c.prepareStatement("""
                        UPDATE transferencia_pix
                           SET reservada_ate = ?
                         WHERE id = ?
                           AND situacao IN %s
                           AND proxima_tentativa_em <= ?
                           AND (reservada_ate IS NULL OR reservada_ate < ?)
                        RETURNING *
                        """.formatted(EM_ANDAMENTO))) {
            Jdbc.instante(sql, 1, agora.plus(validade));
            sql.setObject(2, id);
            Jdbc.instante(sql, 3, agora);
            Jdbc.instante(sql, 4, agora);
            try (ResultSet rs = sql.executeQuery()) {
                return rs.next() ? Optional.of(paraTransferencia(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw Jdbc.erro(e);
        }
    }

    @Override
    public void salvar(TransferenciaPix t) {
        var e = t.estado();
        try (Connection c = banco.getConnection();
                PreparedStatement sql = c.prepareStatement("""
                        UPDATE transferencia_pix
                           SET situacao = ?, motivo = ?, lancamento_envio_id = ?, lancamento_recebimento_id = ?,
                               lancamento_estorno_id = ?, tentativas = ?, proxima_tentativa_em = ?,
                               finalizada_em = ?, reservada_ate = NULL
                         WHERE id = ?
                        """)) {
            sql.setString(1, e.situacao().name());
            sql.setString(2, e.motivo());
            sql.setObject(3, e.lancamentoEnvioId());
            sql.setObject(4, e.lancamentoRecebimentoId());
            sql.setObject(5, e.lancamentoEstornoId());
            sql.setInt(6, e.tentativas());
            Jdbc.instante(sql, 7, e.proximaTentativaEm());
            Jdbc.instante(sql, 8, e.finalizadaEm());
            sql.setObject(9, e.id());
            if (sql.executeUpdate() != 1) {
                throw new IllegalStateException("Transferência %s não encontrada para salvar".formatted(e.id()));
            }
        } catch (SQLException ex) {
            throw Jdbc.erro(ex);
        }
    }

    @Override
    public List<UUID> prontasParaRetomar(Instant agora, int limite) {
        try (Connection c = banco.getConnection();
                PreparedStatement sql = c.prepareStatement("""
                        SELECT id FROM transferencia_pix
                         WHERE situacao IN %s
                           AND proxima_tentativa_em <= ?
                           AND (reservada_ate IS NULL OR reservada_ate < ?)
                         ORDER BY proxima_tentativa_em
                         LIMIT ?
                        """.formatted(EM_ANDAMENTO))) {
            Jdbc.instante(sql, 1, agora);
            Jdbc.instante(sql, 2, agora);
            sql.setInt(3, limite);
            try (ResultSet rs = sql.executeQuery()) {
                List<UUID> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(Jdbc.uuid(rs, "id"));
                }
                return ids;
            }
        } catch (SQLException e) {
            throw Jdbc.erro(e);
        }
    }

    private Optional<TransferenciaPix> consultar(String consulta, Object parametro) {
        try (Connection c = banco.getConnection();
                PreparedStatement sql = c.prepareStatement(consulta)) {
            sql.setObject(1, parametro);
            try (ResultSet rs = sql.executeQuery()) {
                return rs.next() ? Optional.of(paraTransferencia(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw Jdbc.erro(e);
        }
    }

    private static TransferenciaPix paraTransferencia(ResultSet rs) throws SQLException {
        return TransferenciaPix.reconstituir(new TransferenciaPix.Estado(
                Jdbc.uuid(rs, "id"),
                rs.getString("chave_idempotencia"),
                new Participante(Jdbc.uuid(rs, "pagador_conta_id"), rs.getString("pagador_nome")),
                new Participante(Jdbc.uuid(rs, "recebedor_conta_id"), rs.getString("recebedor_nome")),
                new ChavePix(TipoChave.valueOf(rs.getString("recebedor_tipo_chave")), rs.getString("recebedor_chave")),
                Dinheiro.de(rs.getBigDecimal("valor"), Dinheiro.REAL),
                rs.getString("descricao"),
                SituacaoTransferencia.valueOf(rs.getString("situacao")),
                rs.getString("motivo"),
                Jdbc.uuid(rs, "lancamento_envio_id"),
                Jdbc.uuid(rs, "lancamento_recebimento_id"),
                Jdbc.uuid(rs, "lancamento_estorno_id"),
                rs.getInt("tentativas"),
                Jdbc.instante(rs, "proxima_tentativa_em"),
                Jdbc.instante(rs, "criada_em"),
                Jdbc.instante(rs, "finalizada_em")));
    }
}
