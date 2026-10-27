package io.github.alanjoffre.ledgerly.razao.adaptadores.saida.eventos;

import io.github.alanjoffre.ledgerly.razao.aplicacao.porta.CaixaDeSaida;
import io.github.alanjoffre.ledgerly.razao.dominio.ContaRazao;
import io.github.alanjoffre.ledgerly.razao.dominio.Lancamento;
import io.github.alanjoffre.ledgerly.razao.dominio.Partida;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adaptador de saída: grava os eventos na tabela {@code outbox} (ADR-0013).
 *
 * <p>Usa o mesmo {@link JdbcClient} — e portanto a mesma conexão e a mesma transação — do
 * lançamento. Se o lançamento for desfeito, o evento some junto; se for confirmado, o evento fica
 * esperando o {@link PublicadorDaCaixaDeSaida}.
 *
 * <p>O formato do envelope e de {@code dados} é o contrato em {@code docs/arquitetura/eventos.md}.
 */
@Repository
class JdbcCaixaDeSaida implements CaixaDeSaida {

    static final String TOPICO_LANCAMENTOS = "razao.lancamentos.v1";
    static final int VERSAO = 1;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JdbcCaixaDeSaida(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void lancamentoRegistrado(Lancamento lancamento, List<Long> sequencias) {
        if (sequencias.size() != lancamento.partidas().size()) {
            throw new IllegalArgumentException("Uma sequência por partida: %d partidas, %d sequências"
                    .formatted(lancamento.partidas().size(), sequencias.size()));
        }
        ObjectNode dados = json.createObjectNode();
        dados.put("lancamentoId", lancamento.id().toString());
        dados.put("tipo", lancamento.tipo().name());
        dados.put("descricao", lancamento.descricao());
        dados.put("moeda", lancamento.valor().moeda().getCurrencyCode());
        dados.put("registradoEm", lancamento.registradoEm().toString());
        ArrayNode partidas = dados.putArray("partidas");
        for (int i = 0; i < sequencias.size(); i++) {
            Partida partida = lancamento.partidas().get(i);
            partidas.addObject()
                    .put("sequencia", sequencias.get(i))
                    .put("contaId", partida.contaId().toString())
                    .put("contaInterna", ContaRazao.ehInterna(partida.contaId()))
                    .put("tipo", partida.tipo().name())
                    .put("valor", partida.valor().valor().toPlainString())
                    .put("saldoApos", partida.saldoApos().valor().toPlainString());
        }
        gravar(TOPICO_LANCAMENTOS, lancamento.id().toString(), "LancamentoRegistrado", lancamento.registradoEm(), dados);
    }

    private void gravar(String topico, String chave, String tipo, Instant ocorridoEm, ObjectNode dados) {
        UUID eventoId = UUID.randomUUID();
        ObjectNode envelope = json.createObjectNode();
        envelope.put("eventoId", eventoId.toString());
        envelope.put("tipo", tipo);
        envelope.put("versao", VERSAO);
        envelope.put("ocorridoEm", ocorridoEm.toString());
        envelope.set("dados", dados);

        jdbc.sql("""
                        INSERT INTO outbox (evento_id, topico, chave, tipo, payload)
                        VALUES (:eventoId, :topico, :chave, :tipo, CAST(:payload AS jsonb))
                        """)
                .param("eventoId", eventoId)
                .param("topico", topico)
                .param("chave", chave)
                .param("tipo", tipo)
                .param("payload", json.writeValueAsString(envelope))
                .update();
    }
}
