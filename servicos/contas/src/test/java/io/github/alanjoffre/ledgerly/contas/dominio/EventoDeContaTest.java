package io.github.alanjoffre.ledgerly.contas.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.alanjoffre.ledgerly.contas.dominio.EventoDeConta.ContaAberta;
import io.github.alanjoffre.ledgerly.contas.dominio.EventoDeConta.ContaBloqueada;
import io.github.alanjoffre.ledgerly.contas.dominio.EventoDeConta.ContaDesbloqueada;
import io.github.alanjoffre.ledgerly.contas.dominio.EventoDeConta.ContaEncerrada;
import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Eventos de conta")
class EventoDeContaTest {

    private static final UUID CONTA = UUID.randomUUID();

    @Test
    @DisplayName("o horário é cortado em microssegundos (a precisão do PostgreSQL), em todos os tipos")
    void microssegundos() {
        Instant comNanos = Instant.parse("2026-10-27T18:30:00.123456789Z");
        Instant esperado = Instant.parse("2026-10-27T18:30:00.123456Z");

        assertThat(new ContaAberta(CONTA, UUID.randomUUID(), Dinheiro.REAL, comNanos).ocorridoEm()).isEqualTo(esperado);
        assertThat(new ContaBloqueada(CONTA, comNanos).ocorridoEm()).isEqualTo(esperado);
        assertThat(new ContaDesbloqueada(CONTA, comNanos).ocorridoEm()).isEqualTo(esperado);
        assertThat(new ContaEncerrada(CONTA, comNanos).ocorridoEm()).isEqualTo(esperado);
    }

    @Test
    @DisplayName("campos obrigatórios")
    void obrigatorios() {
        Instant agora = Instant.now();
        assertThatNullPointerException().isThrownBy(() -> new ContaAberta(null, UUID.randomUUID(), Dinheiro.REAL, agora));
        assertThatNullPointerException().isThrownBy(() -> new ContaAberta(CONTA, null, Dinheiro.REAL, agora));
        assertThatNullPointerException().isThrownBy(() -> new ContaAberta(CONTA, UUID.randomUUID(), null, agora));
        assertThatNullPointerException().isThrownBy(() -> new ContaBloqueada(null, agora));
        assertThatNullPointerException().isThrownBy(() -> new ContaDesbloqueada(null, agora));
        assertThatNullPointerException().isThrownBy(() -> new ContaEncerrada(null, agora));
        assertThatNullPointerException().isThrownBy(() -> new ContaEncerrada(CONTA, null));
    }
}
