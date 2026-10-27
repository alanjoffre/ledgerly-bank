package io.github.alanjoffre.ledgerly.extrato.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("Linha do extrato")
class MovimentacaoTest {

    private static final Instant QUANDO = Instant.parse("2026-10-27T18:30:00.123456Z");
    private static final UUID PIX = UUID.randomUUID();

    private static ParteDoRazao razao(TipoOperacao operacao, TipoPartida tipo) {
        return new ParteDoRazao(7, operacao, tipo, Dinheiro.reais("10.00"), Dinheiro.reais("90.00"), "Aluguel", QUANDO);
    }

    private static Movimentacao linha(TipoOperacao operacao, TipoPartida tipo, ParteDoPix pix) {
        return new Movimentacao(UUID.randomUUID(), UUID.randomUUID(), razao(operacao, tipo), pix);
    }

    @Nested
    @DisplayName("descrição com o pedaço do Pix")
    class ComPix {

        @Test
        @DisplayName("enviado: diz para quem")
        void enviado() {
            var pix = new ParteDoPix(PIX, PapelNoPix.ENVIADO, SituacaoPix.CONCLUIDO, "Maria Souza", "EMAIL", "m***@exemplo.com", null);
            assertThat(linha(TipoOperacao.PIX_ENVIO, TipoPartida.DEBITO, pix).descricao()).isEqualTo("Pix enviado para Maria Souza");
        }

        @Test
        @DisplayName("recebido: diz de quem")
        void recebido() {
            var pix = new ParteDoPix(PIX, PapelNoPix.RECEBIDO, SituacaoPix.CONCLUIDO, "João da Silva", null, null, null);
            assertThat(linha(TipoOperacao.PIX_RECEBIMENTO, TipoPartida.CREDITO, pix).descricao()).isEqualTo("Pix recebido de João da Silva");
        }

        @Test
        @DisplayName("estorno: diz para quem era e o motivo")
        void estorno() {
            var pix = new ParteDoPix(PIX, PapelNoPix.ESTORNO, SituacaoPix.ESTORNADO, "Maria Souza", null, null, "conta de destino encerrada");
            assertThat(linha(TipoOperacao.PIX_ESTORNO, TipoPartida.CREDITO, pix).descricao())
                    .isEqualTo("Estorno do Pix para Maria Souza: conta de destino encerrada");
        }

        @Test
        @DisplayName("estorno sem motivo: sem os dois-pontos")
        void estornoSemMotivo() {
            for (String motivo : new String[] {null, " "}) {
                var pix = new ParteDoPix(PIX, PapelNoPix.ESTORNO, SituacaoPix.ESTORNADO, "Maria Souza", null, null, motivo);
                assertThat(linha(TipoOperacao.PIX_ESTORNO, TipoPartida.CREDITO, pix).descricao()).isEqualTo("Estorno do Pix para Maria Souza");
            }
        }
    }

    @ParameterizedTest(name = "{0} {1} → \"{2}\"")
    @DisplayName("descrição sem o pedaço do Pix: pela operação")
    @CsvSource({
        "DEPOSITO, CREDITO, Depósito",
        "SAQUE, DEBITO, Saque",
        "TRANSFERENCIA, DEBITO, Transferência enviada",
        "TRANSFERENCIA, CREDITO, Transferência recebida",
        "PIX_ENVIO, DEBITO, Pix enviado",
        "PIX_RECEBIMENTO, CREDITO, Pix recebido",
        "PIX_ESTORNO, CREDITO, Estorno de Pix"
    })
    void semPix(TipoOperacao operacao, TipoPartida tipo, String esperada) {
        assertThat(linha(operacao, tipo, null).descricao()).isEqualTo(esperada);
    }

    @Test
    @DisplayName("a hora é cortada em milissegundos, a precisão do MongoDB")
    void horaEmMilissegundos() {
        assertThat(razao(TipoOperacao.DEPOSITO, TipoPartida.CREDITO).registradoEm())
                .isEqualTo(Instant.parse("2026-10-27T18:30:00.123Z"));
    }

    @Test
    @DisplayName("sequência precisa ser positiva; campos obrigatórios são cobrados")
    void validacoes() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ParteDoRazao(
                0, TipoOperacao.DEPOSITO, TipoPartida.CREDITO, Dinheiro.reais("1"), Dinheiro.reais("1"), null, QUANDO));
        assertThatNullPointerException().isThrownBy(() -> new Movimentacao(UUID.randomUUID(), UUID.randomUUID(), null, null))
                .withMessageContaining("razão");
        assertThatNullPointerException().isThrownBy(() -> new ParteDoPix(PIX, PapelNoPix.ENVIADO, SituacaoPix.CONCLUIDO, null, null, null, null))
                .withMessageContaining("contraparte");
    }

    @Nested
    @DisplayName("saldo")
    class SaldoDaConta {

        private final UUID conta = UUID.randomUUID();

        private Saldo saldo(long sequencia) {
            return new Saldo(conta, Dinheiro.reais("1.00"), sequencia, QUANDO);
        }

        @Test
        @DisplayName("só substitui o guardado se veio de partida posterior")
        void maisNovo() {
            assertThat(saldo(5).ehMaisNovoQue(null)).isTrue();
            assertThat(saldo(5).ehMaisNovoQue(saldo(4))).isTrue();
            assertThat(saldo(5).ehMaisNovoQue(saldo(5))).isFalse();
            assertThat(saldo(5).ehMaisNovoQue(saldo(6))).isFalse();
        }

        @Test
        @DisplayName("o saldo depois de uma partida vem do saldoApos e da sequência dela")
        void depoisDe() {
            Saldo saldo = Saldo.depoisDe(conta, razao(TipoOperacao.DEPOSITO, TipoPartida.CREDITO));
            assertThat(saldo.valor()).isEqualTo(Dinheiro.reais("90.00"));
            assertThat(saldo.sequencia()).isEqualTo(7);
            assertThat(saldo.atualizadoEm()).isEqualTo(Instant.parse("2026-10-27T18:30:00.123Z"));
        }
    }

    @Test
    @DisplayName("conta não encontrada tem código estável")
    void codigoDoErro() {
        assertThat(new ContaNaoEncontradaException("x").codigo()).isEqualTo("conta-nao-encontrada");
    }
}
