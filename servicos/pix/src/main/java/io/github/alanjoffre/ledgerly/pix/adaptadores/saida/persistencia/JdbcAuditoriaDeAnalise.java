package io.github.alanjoffre.ledgerly.pix.adaptadores.saida.persistencia;

import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.AuditoriaDeAnalise;
import io.github.alanjoffre.ledgerly.pix.dominio.DecisaoDeAnalise;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/** Adaptador de saída: a trilha de auditoria das decisões do backoffice. Só insere; nunca altera. */
@ApplicationScoped
class JdbcAuditoriaDeAnalise implements AuditoriaDeAnalise {

    private final DataSource banco;

    JdbcAuditoriaDeAnalise(DataSource banco) {
        this.banco = banco;
    }

    @Override
    public void registrar(DecisaoDeAnalise d) {
        try (Connection c = banco.getConnection();
                PreparedStatement sql = c.prepareStatement("""
                        INSERT INTO decisao_de_analise (
                            id, transferencia_id, analista_id, analista_nome, decisao,
                            conta_destino_id, justificativa, decidida_em)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
            sql.setObject(1, d.id());
            sql.setObject(2, d.transferenciaId());
            sql.setString(3, d.analistaId());
            sql.setString(4, d.analistaNome());
            sql.setString(5, d.decisao().name());
            sql.setObject(6, d.contaDestinoId());
            sql.setString(7, d.justificativa());
            Jdbc.instante(sql, 8, d.decididaEm());
            sql.executeUpdate();
        } catch (SQLException e) {
            throw Jdbc.erro(e);
        }
    }

    @Override
    public List<DecisaoDeAnalise> daTransferencia(UUID transferenciaId) {
        try (Connection c = banco.getConnection();
                PreparedStatement sql = c.prepareStatement(
                        "SELECT * FROM decisao_de_analise WHERE transferencia_id = ? ORDER BY decidida_em")) {
            sql.setObject(1, transferenciaId);
            try (ResultSet rs = sql.executeQuery()) {
                List<DecisaoDeAnalise> lista = new ArrayList<>();
                while (rs.next()) {
                    lista.add(new DecisaoDeAnalise(
                            Jdbc.uuid(rs, "id"),
                            Jdbc.uuid(rs, "transferencia_id"),
                            rs.getString("analista_id"),
                            rs.getString("analista_nome"),
                            DecisaoDeAnalise.Decisao.valueOf(rs.getString("decisao")),
                            Jdbc.uuid(rs, "conta_destino_id"),
                            rs.getString("justificativa"),
                            Jdbc.instante(rs, "decidida_em")));
                }
                return lista;
            }
        } catch (SQLException e) {
            throw Jdbc.erro(e);
        }
    }
}
