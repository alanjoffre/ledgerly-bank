package io.github.alanjoffre.ledgerly.contas.adaptadores.saida.eventos;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.alanjoffre.ledgerly.contas.integracao.TesteDeIntegracao;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A limpeza do Outbox: só some o que já foi publicado há mais tempo que a retenção. */
@DisplayName("Publicador do Outbox: limpeza")
class PublicadorDoOutboxIT extends TesteDeIntegracao {

    @Autowired
    private PublicadorDoOutbox publicador;

    @Test
    @DisplayName("apaga publicados há mais de 7 dias; mantém os recentes e os ainda pendentes")
    void limpeza() {
        UUID antigo = inserir("now() - interval '8 days'", "now() - interval '8 days'");
        UUID recente = inserir("now() - interval '1 day'", "now() - interval '1 day'");
        // Pendente velho: nunca é apagado, por mais antigo que seja — ainda precisa ser entregue.
        // (Se o publicador agendado o entregar antes da limpeza, ele vira "publicado agora" e também
        // fica: o resultado esperado é o mesmo.)
        UUID pendente = inserir("now() - interval '30 days'", null);

        assertThat(publicador.apagarPublicadosAntigos()).isEqualTo(1);

        assertThat(existe(antigo)).isFalse();
        assertThat(existe(recente)).isTrue();
        assertThat(existe(pendente)).isTrue();
    }

    private UUID inserir(String criadoEm, String publicadoEm) {
        UUID evento = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox (evento_id, topico, chave, tipo, payload, criado_em, publicado_em)
                VALUES (?, ?, ?, 'Teste', '{}'::jsonb, %s, %s)
                """.formatted(criadoEm, publicadoEm == null ? "NULL" : publicadoEm), evento, TOPICO, evento.toString());
        return evento;
    }

    private boolean existe(UUID evento) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE evento_id = ?", Long.class, evento) == 1;
    }
}
