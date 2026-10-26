package io.github.alanjoffre.ledgerly.razao.dominio;

import static io.github.alanjoffre.ledgerly.nucleo.Dinheiro.reais;
import static io.github.alanjoffre.ledgerly.razao.dominio.PedidoDePartida.credito;
import static io.github.alanjoffre.ledgerly.razao.dominio.PedidoDePartida.debito;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Escrituração")
class EscrituracaoTest {

    private static final ChaveIdempotencia CHAVE = new ChaveIdempotencia("k");
    private static final Instant AGORA = Instant.parse("2026-10-26T12:00:00Z");

    private final ContaRazao origem = ContaRazao.abrirContaDeCliente(UUID.randomUUID(), Dinheiro.REAL);
    private final ContaRazao destino = ContaRazao.abrirContaDeCliente(UUID.randomUUID(), Dinheiro.REAL);

    private Map<UUID, ContaRazao> contas() {
        return Map.of(origem.id(), origem, destino.id(), destino);
    }

    @Test
    @DisplayName("transfere: debita a origem, credita o destino e registra o saldo após cada partida")
    void transfere() {
        origem.aplicar(TipoPartida.CREDITO, reais("100.00"));

        Lancamento l = Escrituracao.escriturar(CHAVE, TipoOperacao.TRANSFERENCIA, "aluguel",
                List.of(debito(origem.id(), reais("30.00")), credito(destino.id(), reais("30.00"))), contas(), AGORA);

        assertThat(origem.saldo()).isEqualTo(reais("70.00"));
        assertThat(destino.saldo()).isEqualTo(reais("30.00"));
        assertThat(l.partidaDa(origem.id()).saldoApos()).isEqualTo(reais("70.00"));
        assertThat(l.partidaDa(destino.id()).saldoApos()).isEqualTo(reais("30.00"));
        assertThat(l.registradoEm()).isEqualTo(AGORA);
        assertThat(l.descricao()).isEqualTo("aluguel");
    }

    @Test
    @DisplayName("lançamento desbalanceado é recusado ANTES de mexer em qualquer conta")
    void desbalanceadoNaoMexeNasContas() {
        origem.aplicar(TipoPartida.CREDITO, reais("100.00"));

        assertThatThrownBy(() -> Escrituracao.escriturar(CHAVE, TipoOperacao.TRANSFERENCIA, null,
                        List.of(debito(origem.id(), reais("30.00")), credito(destino.id(), reais("20.00"))),
                        contas(), AGORA))
                .isInstanceOf(LancamentoInvalidoException.class);
        assertThat(origem.saldo()).isEqualTo(reais("100.00"));
        assertThat(destino.saldo().ehZero()).isTrue();
    }

    @Test
    @DisplayName("saldo insuficiente na origem: exceção (quem chama desfaz a transação)")
    void saldoInsuficiente() {
        assertThatThrownBy(() -> Escrituracao.escriturar(CHAVE, TipoOperacao.TRANSFERENCIA, null,
                        List.of(debito(origem.id(), reais("0.01")), credito(destino.id(), reais("0.01"))),
                        contas(), AGORA))
                .isInstanceOf(SaldoInsuficienteException.class);
    }

    @Test
    @DisplayName("conta ausente do mapa: ContaNaoEncontrada")
    void contaAusente() {
        UUID fantasma = UUID.randomUUID();
        assertThatThrownBy(() -> Escrituracao.escriturar(CHAVE, TipoOperacao.TRANSFERENCIA, null,
                        List.of(debito(origem.id(), reais("1")), credito(fantasma, reais("1"))), Map.of(), AGORA))
                .isInstanceOf(ContaNaoEncontradaException.class);
    }

    @Test
    @DisplayName("descrição longa é recusada antes de mexer nas contas")
    void descricaoLonga() {
        origem.aplicar(TipoPartida.CREDITO, reais("10.00"));
        assertThatThrownBy(() -> Escrituracao.escriturar(CHAVE, TipoOperacao.TRANSFERENCIA, "x".repeat(141),
                        List.of(debito(origem.id(), reais("1")), credito(destino.id(), reais("1"))), contas(), AGORA))
                .isInstanceOf(LancamentoInvalidoException.class);
        assertThat(origem.saldo()).isEqualTo(reais("10.00"));
    }
}
