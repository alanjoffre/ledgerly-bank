package io.github.alanjoffre.ledgerly.pix.adaptadores.saida.eventos;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.CaixaDeSaida;
import io.github.alanjoffre.ledgerly.pix.dominio.Participante;
import io.github.alanjoffre.ledgerly.pix.dominio.TransferenciaPix;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Adaptador de saída: grava PixConcluido e PixEstornado na tabela {@code outbox} (ADR-0013), na
 * transação que mudou a situação da transferência. O formato é o contrato em
 * {@code docs/arquitetura/eventos.md}.
 */
@ApplicationScoped
class JdbcCaixaDeSaida implements CaixaDeSaida {

    static final String TOPICO = "pix.transferencias.v1";

    private final DataSource banco;
    private final ObjectMapper json;

    JdbcCaixaDeSaida(DataSource banco, ObjectMapper json) {
        this.banco = banco;
        this.json = json;
    }

    @Override
    public void pixConcluido(TransferenciaPix t) {
        ObjectNode dados = dadosComuns(t);
        dados.put("lancamentoRecebimentoId", t.lancamentoRecebimentoId().toString());
        dados.put("concluidoEm", t.finalizadaEm().toString());
        gravar(t, "PixConcluido", dados);
    }

    @Override
    public void pixEstornado(TransferenciaPix t) {
        ObjectNode dados = dadosComuns(t);
        dados.put("lancamentoEstornoId", t.lancamentoEstornoId().toString());
        dados.put("motivo", t.motivo());
        dados.put("estornadoEm", t.finalizadaEm().toString());
        gravar(t, "PixEstornado", dados);
    }

    private ObjectNode dadosComuns(TransferenciaPix t) {
        ObjectNode dados = json.createObjectNode();
        dados.put("pixId", t.id().toString());
        dados.put("valor", t.valor().valor().toPlainString());
        dados.put("moeda", t.valor().moeda().getCurrencyCode());
        dados.put("descricao", t.descricao());
        participante(dados.putObject("pagador"), t.pagador());
        ObjectNode recebedor = participante(dados.putObject("recebedor"), t.recebedor());
        recebedor.put("tipoChave", t.chaveDoRecebedor().tipo().name());
        // Mascarada: o extrato só mostra a chave; não precisa do dado pessoal inteiro.
        recebedor.put("chave", t.chaveDoRecebedor().mascarada());
        dados.put("lancamentoEnvioId", t.lancamentoEnvioId().toString());
        return dados;
    }

    private static ObjectNode participante(ObjectNode no, Participante participante) {
        return no.put("contaId", participante.contaId().toString()).put("nome", participante.nome());
    }

    private void gravar(TransferenciaPix t, String tipo, ObjectNode dados) {
        UUID eventoId = UUID.randomUUID();
        ObjectNode envelope = json.createObjectNode()
                .put("eventoId", eventoId.toString())
                .put("tipo", tipo)
                .put("versao", 1)
                .put("ocorridoEm", t.finalizadaEm().toString());
        envelope.set("dados", dados);
        try (Connection c = banco.getConnection();
                PreparedStatement sql = c.prepareStatement("""
                        INSERT INTO outbox (evento_id, topico, chave, tipo, payload)
                        VALUES (?, ?, ?, ?, CAST(? AS jsonb))
                        """)) {
            sql.setObject(1, eventoId);
            sql.setString(2, TOPICO);
            sql.setString(3, t.id().toString());
            sql.setString(4, tipo);
            sql.setString(5, json.writeValueAsString(envelope));
            sql.executeUpdate();
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("Não foi possível gravar o evento %s no Outbox".formatted(tipo), e);
        }
    }
}
