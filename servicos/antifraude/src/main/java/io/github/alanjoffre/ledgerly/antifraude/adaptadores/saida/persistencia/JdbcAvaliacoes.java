package io.github.alanjoffre.ledgerly.antifraude.adaptadores.saida.persistencia;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.alanjoffre.ledgerly.antifraude.aplicacao.porta.AvaliacaoRepository;
import io.github.alanjoffre.ledgerly.antifraude.dominio.Avaliacao;
import io.github.alanjoffre.ledgerly.antifraude.dominio.Decisao;
import io.github.alanjoffre.ledgerly.antifraude.dominio.Explicacao;
import io.github.alanjoffre.ledgerly.antifraude.dominio.FatosDoPix;
import io.github.alanjoffre.ledgerly.antifraude.dominio.HistoricoDoPagador;
import io.github.alanjoffre.ledgerly.antifraude.dominio.Motivo;
import io.github.alanjoffre.ledgerly.antifraude.dominio.PedidoDeAvaliacao;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Adaptador de saída: as avaliações no PostgreSQL. Avaliação gravada não muda; só ganha, uma vez, a explicação. */
@ApplicationScoped
class JdbcAvaliacoes implements AvaliacaoRepository {

    /** A média do costume sai dos últimos 20 Pix: o costume de hoje, não o de dois anos atrás. */
    static final int PIX_DA_MEDIA = 20;
    static final Duration UMA_HORA = Duration.ofHours(1);
    private static final TypeReference<List<Motivo>> MOTIVOS = new TypeReference<>() {};

    private final DataSource banco;
    private final ObjectMapper json;

    JdbcAvaliacoes(DataSource banco, ObjectMapper json) {
        this.banco = banco;
        this.json = json;
    }

    @Override
    public Optional<Avaliacao> buscar(UUID transferenciaId) {
        try (Connection c = banco.getConnection();
                PreparedStatement sql = c.prepareStatement("SELECT * FROM avaliacao WHERE transferencia_id = ?")) {
            sql.setObject(1, transferenciaId);
            try (ResultSet rs = sql.executeQuery()) {
                return rs.next() ? Optional.of(ler(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw Jdbc.erro(e);
        }
    }

    /**
     * Os Pix recusados contam na rajada (tentar 10 vezes também é sinal), mas não na média nem no
     * "já pagou esse recebedor": senão o golpe recusado ensinaria o modelo a achar normal o próximo.
     */
    @Override
    public HistoricoDoPagador historico(UUID pagadorContaId, UUID recebedorContaId, Instant antesDe) {
        try (Connection c = banco.getConnection();
                PreparedStatement sql = c.prepareStatement("""
                        SELECT
                            (SELECT avg(valor) FROM (
                                SELECT valor FROM avaliacao
                                 WHERE pagador_conta_id = ? AND instante < ? AND decisao <> 'RECUSAR'
                                 ORDER BY instante DESC LIMIT ?) ultimos) AS media,
                            (SELECT count(*) FROM avaliacao
                              WHERE pagador_conta_id = ? AND instante >= ? AND instante < ?) AS na_hora,
                            EXISTS (SELECT 1 FROM avaliacao
                                     WHERE pagador_conta_id = ? AND recebedor_conta_id = ?
                                       AND instante < ? AND decisao <> 'RECUSAR') AS ja_pagou
                        """)) {
            sql.setObject(1, pagadorContaId);
            Jdbc.instante(sql, 2, antesDe);
            sql.setInt(3, PIX_DA_MEDIA);
            sql.setObject(4, pagadorContaId);
            Jdbc.instante(sql, 5, antesDe.minus(UMA_HORA));
            Jdbc.instante(sql, 6, antesDe);
            sql.setObject(7, pagadorContaId);
            sql.setObject(8, recebedorContaId);
            Jdbc.instante(sql, 9, antesDe);
            try (ResultSet rs = sql.executeQuery()) {
                rs.next();
                BigDecimal media = rs.getBigDecimal("media");
                return new HistoricoDoPagador(
                        media == null ? null : media.setScale(2, RoundingMode.HALF_EVEN),
                        rs.getInt("na_hora"),
                        rs.getBoolean("ja_pagou"));
            }
        } catch (SQLException e) {
            throw Jdbc.erro(e);
        }
    }

    @Override
    public boolean inserir(Avaliacao a) {
        PedidoDeAvaliacao p = a.pedido();
        FatosDoPix f = a.fatos();
        try (Connection c = banco.getConnection();
                PreparedStatement sql = c.prepareStatement("""
                        INSERT INTO avaliacao (
                            transferencia_id, pagador_conta_id, recebedor_conta_id, valor, instante,
                            conta_recebedora_aberta_em, razao_da_media, pix_na_ultima_hora, recebedor_novo,
                            madrugada, idade_conta_recebedora_dias, nota, decisao, motivos, modelo, avaliada_em)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                        ON CONFLICT (transferencia_id) DO NOTHING
                        """)) {
            sql.setObject(1, p.transferenciaId());
            sql.setObject(2, p.pagadorContaId());
            sql.setObject(3, p.recebedorContaId());
            sql.setBigDecimal(4, p.valor());
            Jdbc.instante(sql, 5, p.instante());
            Jdbc.instante(sql, 6, p.contaRecebedoraAbertaEm());
            sql.setBigDecimal(7, f.razaoDaMedia() == null ? null : f.razaoDaMedia().setScale(4, RoundingMode.HALF_EVEN));
            sql.setInt(8, f.pixNaUltimaHora());
            sql.setBoolean(9, f.recebedorNovo());
            sql.setBoolean(10, f.madrugada());
            sql.setLong(11, f.idadeDaContaRecebedoraEmDias());
            sql.setInt(12, a.nota());
            sql.setString(13, a.decisao().name());
            sql.setString(14, json.writeValueAsString(a.motivos()));
            sql.setString(15, a.modelo());
            Jdbc.instante(sql, 16, a.avaliadaEm());
            return sql.executeUpdate() == 1;
        } catch (SQLException e) {
            throw Jdbc.erro(e);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Optional<Explicacao> explicacao(UUID transferenciaId) {
        try (Connection c = banco.getConnection();
                PreparedStatement sql = c.prepareStatement("""
                        SELECT explicacao, explicacao_origem, explicacao_modelo, explicada_em
                          FROM avaliacao WHERE transferencia_id = ? AND explicacao IS NOT NULL
                        """)) {
            sql.setObject(1, transferenciaId);
            try (ResultSet rs = sql.executeQuery()) {
                return rs.next()
                        ? Optional.of(new Explicacao(
                                rs.getString("explicacao"),
                                Explicacao.Origem.valueOf(rs.getString("explicacao_origem")),
                                rs.getString("explicacao_modelo"),
                                Jdbc.instante(rs, "explicada_em")))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw Jdbc.erro(e);
        }
    }

    @Override
    public boolean guardarExplicacao(UUID transferenciaId, Explicacao e) {
        try (Connection c = banco.getConnection();
                PreparedStatement sql = c.prepareStatement("""
                        UPDATE avaliacao
                           SET explicacao = ?, explicacao_origem = ?, explicacao_modelo = ?, explicada_em = ?
                         WHERE transferencia_id = ? AND explicacao IS NULL
                        """)) {
            sql.setString(1, e.texto());
            sql.setString(2, e.origem().name());
            sql.setString(3, e.modelo());
            Jdbc.instante(sql, 4, e.explicadaEm());
            sql.setObject(5, transferenciaId);
            return sql.executeUpdate() == 1;
        } catch (SQLException erro) {
            throw Jdbc.erro(erro);
        }
    }

    private Avaliacao ler(ResultSet rs) throws SQLException {
        var pedido = new PedidoDeAvaliacao(
                Jdbc.uuid(rs, "transferencia_id"),
                Jdbc.uuid(rs, "pagador_conta_id"),
                Jdbc.uuid(rs, "recebedor_conta_id"),
                rs.getBigDecimal("valor"),
                Jdbc.instante(rs, "instante"),
                Jdbc.instante(rs, "conta_recebedora_aberta_em"));
        var fatos = new FatosDoPix(
                pedido.valor(),
                rs.getBigDecimal("razao_da_media"),
                rs.getInt("pix_na_ultima_hora"),
                rs.getBoolean("recebedor_novo"),
                rs.getBoolean("madrugada"),
                rs.getLong("idade_conta_recebedora_dias"));
        try {
            return new Avaliacao(
                    pedido,
                    fatos,
                    rs.getInt("nota"),
                    Decisao.valueOf(rs.getString("decisao")),
                    json.readValue(rs.getString("motivos"), MOTIVOS),
                    rs.getString("modelo"),
                    Jdbc.instante(rs, "avaliada_em"));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("motivos ilegíveis na avaliação " + pedido.transferenciaId(), e);
        }
    }
}
