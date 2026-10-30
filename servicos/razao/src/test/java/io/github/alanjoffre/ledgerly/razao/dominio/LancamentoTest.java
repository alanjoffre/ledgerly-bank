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
        assertThat(l.correspondeA(TipoOperacao.TRANSFERENCIA, List.of(credito(B, reais("10")), debito(A, reais("10"))), null))
                .isTrue();
        assertThat(l.correspondeA(TipoOperacao.TRANSFERENCIA, List.of(debito(A, reais("11")), credito(B, reais("11"))), null))
                .as("valor diferente")
                .isFalse();
        assertThat(l.correspondeA(TipoOperacao.DEPOSITO, List.of(debito(A, reais("10")), credito(B, reais("10"))), null))
                .as("tipo diferente")
                .isFalse();
    }

    @Test
    @DisplayName("correspondeA: a sub-conta do Caixa conta como o Caixa — mudar o número de sub-contas não quebra a repetição")
    void correspondeASubcontas() {
        UUID caixa3 = ContasQuentes.subconta(ContaRazao.CAIXA, 3);
        UUID caixa7 = ContasQuentes.subconta(ContaRazao.CAIXA, 7);
        Lancamento deposito = new Lancamento(UUID.randomUUID(), CHAVE, TipoOperacao.DEPOSITO, null,
                List.of(partida(caixa3, TipoPartida.DEBITO, "10"), partida(A, TipoPartida.CREDITO, "10")), Instant.EPOCH);

        assertThat(deposito.correspondeA(TipoOperacao.DEPOSITO, List.of(debito(caixa7, reais("10")), credito(A, reais("10"))), null))
                .isTrue();
        assertThat(deposito.correspondeA(TipoOperacao.DEPOSITO,
                        List.of(debito(ContasQuentes.subconta(ContaRazao.LIQUIDACAO_PIX, 3), reais("10")), credito(A, reais("10"))), null))
                .as("outra conta-mãe")
                .isFalse();
    }

    @Test
    @DisplayName("envioPixId: só o recebimento e o estorno apontam para um envio, e ele entra na comparação")
    void envioPixId() {
        UUID liquidacao = ContasQuentes.subconta(ContaRazao.LIQUIDACAO_PIX, 2);
        UUID envio = UUID.randomUUID();
        var partidas = List.of(partida(liquidacao, TipoPartida.DEBITO, "5"), partida(A, TipoPartida.CREDITO, "5"));
        Lancamento recebimento = new Lancamento(
                UUID.randomUUID(), CHAVE, TipoOperacao.PIX_RECEBIMENTO, null, partidas, Instant.EPOCH, envio);
        var pedidos = List.of(debito(liquidacao, reais("5")), credito(A, reais("5")));

        assertThat(recebimento.correspondeA(TipoOperacao.PIX_RECEBIMENTO, pedidos, envio)).isTrue();
        assertThat(recebimento.correspondeA(TipoOperacao.PIX_RECEBIMENTO, pedidos, UUID.randomUUID()))
                .as("outro envio")
                .isFalse();
        assertThat(recebimento.subcontaInterna(ContaRazao.LIQUIDACAO_PIX)).contains(liquidacao);
        assertThat(recebimento.subcontaInterna(ContaRazao.CAIXA)).isEmpty();
        assertThat(recebimento.contaDeClienteCom(TipoPartida.CREDITO)).contains(A);
        assertThat(recebimento.contaDeClienteCom(TipoPartida.DEBITO)).isEmpty();
        assertThatThrownBy(() -> new Lancamento(
                        UUID.randomUUID(), CHAVE, TipoOperacao.PIX_ENVIO, null, partidas, Instant.EPOCH, envio))
                .isInstanceOf(LancamentoInvalidoException.class)
                .hasMessageContaining("Só o recebimento e o estorno");
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

    @Test
    @DisplayName("descrição: 140 caracteres passa, 141 não, e sem descrição também passa")
    void limiteDaDescricao() {
        Lancamento.validarDescricao(null);
        Lancamento.validarDescricao("x".repeat(140));
        assertThatThrownBy(() -> Lancamento.validarDescricao("x".repeat(141)))
                .isInstanceOf(LancamentoInvalidoException.class);
    }
}
