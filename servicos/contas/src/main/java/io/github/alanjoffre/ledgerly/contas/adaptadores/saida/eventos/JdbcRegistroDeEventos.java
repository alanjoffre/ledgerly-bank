package io.github.alanjoffre.ledgerly.contas.adaptadores.saida.eventos;

import io.github.alanjoffre.ledgerly.contas.aplicacao.porta.RegistroDeEventos;
import io.github.alanjoffre.ledgerly.contas.dominio.EventoDeConta;
import io.github.alanjoffre.ledgerly.contas.dominio.EventoDeConta.ContaAberta;
import io.github.alanjoffre.ledgerly.contas.dominio.EventoDeConta.ContaBloqueada;
import io.github.alanjoffre.ledgerly.contas.dominio.EventoDeConta.ContaDesbloqueada;
import io.github.alanjoffre.ledgerly.contas.dominio.EventoDeConta.ContaEncerrada;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adaptador de saída: grava o evento na tabela {@code outbox} (ADR-0013), no formato do envelope de
 * {@code docs/arquitetura/eventos.md}, pronto para o {@link PublicadorDoOutbox} mandar ao Kafka.
 *
 * <p><b>Por que SQL direto e não JPA:</b> a tabela é só de passagem, sem regra de domínio, e o
 * publicador precisa de {@code FOR UPDATE SKIP LOCKED}, que o JPA não expressa. O {@link JdbcClient}
 * usa a mesma conexão e a mesma transação do Hibernate: a conta e o evento entram juntos no COMMIT.
 */
@Component
class JdbcRegistroDeEventos implements RegistroDeEventos {

    static final String TOPICO = "contas.eventos.v1";
    static final int VERSAO = 1;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JdbcRegistroDeEventos(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** O envelope comum a todo evento do Ledgerly. */
    record Envelope(UUID eventoId, String tipo, int versao, Instant ocorridoEm, Object dados) {}

    record DadosContaAberta(UUID contaId, UUID clienteId, String moeda) {}

    record DadosConta(UUID contaId) {}

    /**
     * {@code MANDATORY}: exige uma transação já aberta pelo caso de uso. Sem ela o evento seria
     * gravado sozinho — exatamente a escrita dupla que o Outbox existe para evitar.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void registrar(EventoDeConta evento) {
        Object dados = switch (evento) {
            case ContaAberta a -> new DadosContaAberta(a.contaId(), a.clienteId(), a.moeda().getCurrencyCode());
            case ContaBloqueada b -> new DadosConta(b.contaId());
            case ContaDesbloqueada d -> new DadosConta(d.contaId());
            case ContaEncerrada e -> new DadosConta(e.contaId());
        };
        String tipo = evento.getClass().getSimpleName();
        Envelope envelope = new Envelope(UUID.randomUUID(), tipo, VERSAO, evento.ocorridoEm(), dados);
        jdbc.sql("""
                        INSERT INTO outbox (evento_id, topico, chave, tipo, payload)
                        VALUES (:eventoId, :topico, :chave, :tipo, CAST(:payload AS JSONB))
                        """)
                .param("eventoId", envelope.eventoId())
                .param("topico", TOPICO)
                .param("chave", evento.contaId().toString())
                .param("tipo", tipo)
                .param("payload", json.writeValueAsString(envelope))
                .update();
    }
}
