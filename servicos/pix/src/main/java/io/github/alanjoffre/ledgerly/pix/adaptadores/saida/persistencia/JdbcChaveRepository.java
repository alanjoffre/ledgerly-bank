package io.github.alanjoffre.ledgerly.pix.adaptadores.saida.persistencia;

import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.ChaveRepository;
import io.github.alanjoffre.ledgerly.pix.dominio.ChaveJaCadastradaException;
import io.github.alanjoffre.ledgerly.pix.dominio.ChavePix;
import io.github.alanjoffre.ledgerly.pix.dominio.ChaveRegistrada;
import io.github.alanjoffre.ledgerly.pix.dominio.TipoChave;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Adaptador de saída: o diretório de chaves no PostgreSQL.
 *
 * <p>Dentro de um método {@code @Transactional}, o pool (Agroal) entrega sempre a conexão da
 * transação em curso: todas as consultas da mesma operação entram na mesma transação.
 */
@ApplicationScoped
class JdbcChaveRepository implements ChaveRepository {

    private final DataSource banco;

    JdbcChaveRepository(DataSource banco) {
        this.banco = banco;
    }

    @Override
    public void inserir(ChaveRegistrada chave) {
        try (Connection c = banco.getConnection();
                PreparedStatement sql = c.prepareStatement("""
                        INSERT INTO chave_pix (chave, tipo, conta_id, titular_nome, registrada_em)
                        VALUES (?, ?, ?, ?, ?)
                        """)) {
            sql.setString(1, chave.chave().valor());
            sql.setString(2, chave.chave().tipo().name());
            sql.setObject(3, chave.contaId());
            sql.setString(4, chave.titularNome());
            Jdbc.instante(sql, 5, chave.registradaEm());
            sql.executeUpdate();
        } catch (SQLException e) {
            if (Jdbc.VIOLACAO_DE_UNICIDADE.equals(e.getSQLState())) {
                throw new ChaveJaCadastradaException("A chave %s já está registrada".formatted(chave.chave().mascarada()));
            }
            throw Jdbc.erro(e);
        }
    }

    @Override
    public Optional<ChaveRegistrada> buscar(ChavePix chave) {
        List<ChaveRegistrada> achadas = consultar("SELECT * FROM chave_pix WHERE chave = ?", chave.valor());
        return achadas.stream().findFirst();
    }

    @Override
    public List<ChaveRegistrada> listarDaConta(UUID contaId) {
        return consultar("SELECT * FROM chave_pix WHERE conta_id = ? ORDER BY registrada_em, chave", contaId);
    }

    @Override
    public int contarDaContaTravando(UUID contaId) {
        // Trava consultiva (advisory lock) pela conta, até o fim da transação: dois registros
        // simultâneos na mesma conta entram em fila, e a contagem do limite não fura. Um
        // "SELECT ... FOR UPDATE" não serviria: com zero chaves, não há linha para travar.
        try (Connection c = banco.getConnection();
                PreparedStatement trava = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))");
                PreparedStatement conta = c.prepareStatement("SELECT count(*) FROM chave_pix WHERE conta_id = ?")) {
            trava.setString(1, contaId.toString());
            trava.execute();
            conta.setObject(1, contaId);
            try (ResultSet rs = conta.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw Jdbc.erro(e);
        }
    }

    @Override
    public boolean excluir(ChavePix chave) {
        try (Connection c = banco.getConnection();
                PreparedStatement sql = c.prepareStatement("DELETE FROM chave_pix WHERE chave = ?")) {
            sql.setString(1, chave.valor());
            return sql.executeUpdate() == 1;
        } catch (SQLException e) {
            throw Jdbc.erro(e);
        }
    }

    private List<ChaveRegistrada> consultar(String consulta, Object parametro) {
        try (Connection c = banco.getConnection();
                PreparedStatement sql = c.prepareStatement(consulta)) {
            sql.setObject(1, parametro);
            try (ResultSet rs = sql.executeQuery()) {
                List<ChaveRegistrada> chaves = new ArrayList<>();
                while (rs.next()) {
                    chaves.add(new ChaveRegistrada(
                            new ChavePix(TipoChave.valueOf(rs.getString("tipo")), rs.getString("chave")),
                            Jdbc.uuid(rs, "conta_id"),
                            rs.getString("titular_nome"),
                            Jdbc.instante(rs, "registrada_em")));
                }
                return chaves;
            }
        } catch (SQLException e) {
            throw Jdbc.erro(e);
        }
    }
}
