package io.github.alanjoffre.ledgerly.razao.dominio;

import static io.github.alanjoffre.ledgerly.nucleo.Dinheiro.reais;
import static io.github.alanjoffre.ledgerly.razao.dominio.PedidoDePartida.credito;
import static io.github.alanjoffre.ledgerly.razao.dominio.PedidoDePartida.debito;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Lançamento e partidas dobradas")
class LancamentoTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();
    private static final ChaveIdempotencia CHAVE = new ChaveIdempotencia("chave-1");

    private static Lancamento lancamento(List<Partida> partidas) {
        return new Lancamento(UUID.randomUUID(), CHAVE, TipoOperacao.TRANSFERENCIA, "teste", partidas, Instant.EPOCH);
    }

    private static Partida partida(UUID conta, TipoPartida tipo, String valor) {
        return new Partida(conta, tipo, reais(valor), reais("0"));
    }

    @Test
    @DisplayName("débitos iguais aos créditos: aceito")
    void balanceado() {
        Lancamento l = lancamento(List.of(
                partida(A, TipoPartida.DEBITO, "100.00"),
                partida(B, TipoPartida.CREDITO, "60.00"),
                partida(C, TipoPartida.CREDITO, "40.00")));
        assertThat(l.valor()).isEqualTo(reais("100.00"));
        assertThat(l.partidaDa(C).valor()).isEqualTo(reais("40.00"));
    }

    @Test
    @DisplayName("débitos diferentes dos créditos: recusado (o dinheiro não nasce nem some)")
    void desbalanceado() {
        assertThatThrownBy(() -> lancamento(List.of(
                        partida(A, TipoPartida.DEBITO, "100.00"), partida(B, TipoPartida.CREDITO, "99.99"))))
                .isInstanceOf(LancamentoInvalidoException.class)
                .hasMessageContaining("débitos (BRL 100.00) diferentes de créditos (BRL 99.99)");
    }

    @Test
    @DisplayName("uma partida só: recusado")
    void umaPartida() {
        assertThatThrownBy(() -> Lancamento.exigirPartidasDobradas(List.of(debito(A, reais("1")))))
                .isInstanceOf(LancamentoInvalidoException.class)
                .hasMessageContaining("pelo menos duas");
    }

    @Test
    @DisplayName("a mesma conta dos dois lados: recusado")
    void mesmaConta() {
        assertThatThrownBy(() -> Lancamento.exigirPartidasDobradas(
                        List.of(debito(A, reais("1")), credito(A, reais("1")))))
                .isInstanceOf(LancamentoInvalidoException.class)
                .hasMessageContaining("mais de uma vez");
    }

    @Test
    @DisplayName("moedas diferentes: recusado")
    void moedasDiferentes() {
        Dinheiro dolar = Dinheiro.de(BigDecimal.ONE, Currency.getInstance("USD"));
        assertThatThrownBy(() -> Lancamento.exigirPartidasDobradas(List.of(debito(A, reais("1")), credito(B, dolar))))
                .isInstanceOf(LancamentoInvalidoException.class)
                .hasMessageContaining("mesma moeda");
    }

    @Test
    @DisplayName("valor zero ou negativo numa partida: recusado")
    void valorNaoPositivo() {
        assertThatThrownBy(() -> debito(A, reais("0"))).isInstanceOf(LancamentoInvalidoException.class);
        assertThatThrownBy(() -> partida(A, TipoPartida.DEBITO, "-1")).isInstanceOf(LancamentoInvalidoException.class);
    }

    @Test
    @DisplayName("descrição com mais de 140 caracteres: recusado")
    void descricaoLonga() {
        assertThatThrownBy(() -> new Lancamento(UUID.randomUUID(), CHAVE, TipoOperacao.DEPOSITO, "x".repeat(141),
                        List.of(partida(A, TipoPartida.DEBITO, "1"), partida(B, TipoPartida.CREDITO, "1")),
                        Instant.EPOCH))
                .isInstanceOf(LancamentoInvalidoException.class);
    }

    @Test
    @DisplayName("correspondeA: mesmo tipo e mesmas partidas (em qualquer ordem) = mesmo pedido")
    void correspondeA() {
        Lancamento l = lancamento(List.of(partida(A, TipoPartida.DEBITO, "10"), partida(B, TipoPartida.CREDITO, "10")));
        assertThat(l.correspondeA(TipoOperacao.TRANSFERENCIA, List.of(credito(B, reais("10")), debito(A, reais("10")))))
                .isTrue();
        assertThat(l.correspondeA(TipoOperacao.TRANSFERENCIA, List.of(debito(A, reais("11")), credito(B, reais("11")))))
                .as("valor diferente")
                .isFalse();
        assertThat(l.correspondeA(TipoOperacao.DEPOSITO, List.of(debito(A, reais("10")), credito(B, reais("10")))))
                .as("tipo diferente")
                .isFalse();
    }

    @Test
    @DisplayName("partidaDa: conta que não participa é erro")
    void partidaDaContaAusente() {
        Lancamento l = lancamento(List.of(partida(A, TipoPartida.DEBITO, "10"), partida(B, TipoPartida.CREDITO, "10")));
        assertThatThrownBy(() -> l.partidaDa(C)).isInstanceOf(ContaNaoEncontradaException.class);
    }

    @Test
    @DisplayName("a hora é guardada em microssegundos, a precisão do PostgreSQL")
    void horaEmMicrossegundos() {
        Lancamento l = new Lancamento(UUID.randomUUID(), CHAVE, TipoOperacao.DEPOSITO, null,
                List.of(partida(A, TipoPartida.DEBITO, "1"), partida(B, TipoPartida.CREDITO, "1")),
                Instant.parse("2026-10-26T12:00:00.123456789Z"));
        assertThat(l.registradoEm()).isEqualTo(Instant.parse("2026-10-26T12:00:00.123456Z"));
    }

    @Test
    @DisplayName("a lista de partidas é imutável")
    void imutavel() {
        Lancamento l = lancamento(List.of(partida(A, TipoPartida.DEBITO, "10"), partida(B, TipoPartida.CREDITO, "10")));
        assertThatThrownBy(() -> l.partidas().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
