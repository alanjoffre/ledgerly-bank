package io.github.alanjoffre.ledgerly.pix.adaptadores.saida.persistencia;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Pequenas conversões entre Java e JDBC, para os repositórios não repetirem o mesmo código. */
final class Jdbc {

    /** Código do PostgreSQL para "violou um UNIQUE ou PRIMARY KEY". */
    static final String VIOLACAO_DE_UNICIDADE = "23505";

    private Jdbc() {}

    static void instante(PreparedStatement sql, int posicao, Instant instante) throws SQLException {
        sql.setObject(posicao, instante == null ? null : OffsetDateTime.ofInstant(instante, ZoneOffset.UTC));
    }

    static Instant instante(ResultSet rs, String coluna) throws SQLException {
        OffsetDateTime valor = rs.getObject(coluna, OffsetDateTime.class);
        return valor == null ? null : valor.toInstant();
    }

    static UUID uuid(ResultSet rs, String coluna) throws SQLException {
        return rs.getObject(coluna, UUID.class);
    }

    /** Erro de banco inesperado: sobe como exceção não verificada, e a transação é desfeita. */
    static IllegalStateException erro(SQLException e) {
        return new IllegalStateException("Erro no banco do Pix: " + e.getMessage(), e);
    }
}
