package io.github.alanjoffre.ledgerly.razao.integracao;

import static io.github.alanjoffre.ledgerly.nucleo.Dinheiro.reais;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import io.github.alanjoffre.ledgerly.razao.aplicacao.Comandos;
import io.github.alanjoffre.ledgerly.razao.aplicacao.ResultadoLancamento;
import io.github.alanjoffre.ledgerly.razao.dominio.ChaveIdempotencia;
import io.github.alanjoffre.ledgerly.razao.dominio.ContaInativaException;
import io.github.alanjoffre.ledgerly.razao.dominio.ContaRazao;
import io.github.alanjoffre.ledgerly.razao.dominio.OperacaoNaoPermitidaException;
import io.github.alanjoffre.ledgerly.razao.dominio.SaldoInsuficienteException;
import io.github.alanjoffre.ledgerly.razao.dominio.SituacaoConta;
import io.github.alanjoffre.ledgerly.razao.dominio.TipoOperacao;
import io.github.alanjoffre.ledgerly.razao.dominio.TipoPartida;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Os três movimentos do Pix e a conta interna Liquidação Pix, com PostgreSQL de verdade. */
@DisplayName("Movimentos do Pix no razão")
class PixIT extends TesteDeIntegracao {

    @AfterEach
    void razaoContinuaIntegro() {
        assertThat(contas.verificarIntegridade().integro()).isTrue();
    }

    private ResultadoLancamento enviar(UUID conta, String valor) {
        return lancamentos.enviarPix(new Comandos.MovimentoPix(chave(), conta, reais(valor), "Pix"));
    }

    private ResultadoLancamento receber(UUID conta, String valor) {
        return lancamentos.receberPix(new Comandos.MovimentoPix(chave(), conta, reais(valor), "Pix"));
    }

    private ResultadoLancamento estornar(UUID conta, String valor) {
        return lancamentos.estornarPix(new Comandos.MovimentoPix(chave(), conta, reais(valor), "Estorno"));
    }

    @Nested
    @DisplayName("o caminho do dinheiro")
    class Caminho {

        @Test
        @DisplayName("envio: sai do pagador e fica na Liquidação Pix")
        void envio() {
            UUID pagador = novaContaCom("100.00");
            ResultadoLancamento r = enviar(pagador, "30.00");

            assertThat(r.lancamento().tipo()).isEqualTo(TipoOperacao.PIX_ENVIO);
            assertThat(r.lancamento().partidaDa(pagador).tipo()).isEqualTo(TipoPartida.DEBITO);
            assertThat(saldo(pagador)).isEqualTo(reais("70.00"));
            assertThat(saldo(ContaRazao.LIQUIDACAO_PIX)).isEqualTo(reais("30.00"));
        }

        @Test
        @DisplayName("recebimento: sai da Liquidação Pix e chega ao recebedor")
        void recebimento() {
            UUID pagador = novaContaCom("100.00");
            UUID recebedor = novaConta();
            enviar(pagador, "30.00");
            ResultadoLancamento r = receber(recebedor, "30.00");

            assertThat(r.lancamento().tipo()).isEqualTo(TipoOperacao.PIX_RECEBIMENTO);
            assertThat(saldo(recebedor)).isEqualTo(reais("30.00"));
            assertThat(saldo(ContaRazao.LIQUIDACAO_PIX)).isEqualTo(Dinheiro.zero(Dinheiro.REAL));
        }

        @Test
        @DisplayName("estorno: o dinheiro volta ao pagador num lançamento novo; o envio continua lá")
        void estorno() {
            UUID pagador = novaContaCom("100.00");
            UUID envio = enviar(pagador, "30.00").lancamento().id();
            ResultadoLancamento r = estornar(pagador, "30.00");

            assertThat(r.lancamento().tipo()).isEqualTo(TipoOperacao.PIX_ESTORNO);
            assertThat(r.lancamento().id()).isNotEqualTo(envio);
            assertThat(lancamentos.consultar(envio).tipo()).isEqualTo(TipoOperacao.PIX_ENVIO);
            assertThat(saldo(pagador)).isEqualTo(reais("100.00"));
            assertThat(saldo(ContaRazao.LIQUIDACAO_PIX)).isEqualTo(Dinheiro.zero(Dinheiro.REAL));
        }

        @Test
        @DisplayName("o mesmo passo repetido com a mesma chave não move o dinheiro duas vezes")
        void passoRepetido() {
            UUID pagador = novaContaCom("100.00");
            var comando = new Comandos.MovimentoPix(
                    new ChaveIdempotencia("pix:1:envio"), pagador, reais("30.00"), "Pix");
            ResultadoLancamento primeiro = lancamentos.enviarPix(comando);
            ResultadoLancamento repetido = lancamentos.enviarPix(comando);

            assertThat(repetido.repetido()).isTrue();
            assertThat(repetido.lancamento()).isEqualTo(primeiro.lancamento());
            assertThat(saldo(pagador)).isEqualTo(reais("70.00"));
        }
    }

    @Nested
    @DisplayName("o que o razão recusa")
    class Recusas {

        @Test
        @DisplayName("envio sem saldo: nada se move")
        void envioSemSaldo() {
            UUID pagador = novaContaCom("10.00");
            assertThatThrownBy(() -> enviar(pagador, "10.01")).isInstanceOf(SaldoInsuficienteException.class);
            assertThat(saldo(pagador)).isEqualTo(reais("10.00"));
            assertThat(saldo(ContaRazao.LIQUIDACAO_PIX)).isEqualTo(Dinheiro.zero(Dinheiro.REAL));
        }

        @Test
        @DisplayName("creditar um Pix que nunca saiu de ninguém: a Liquidação Pix não fica negativa")
        void recebimentoSemEnvio() {
            UUID recebedor = novaConta();
            assertThatThrownBy(() -> receber(recebedor, "1.00"))
                    .isInstanceOf(SaldoInsuficienteException.class)
                    .hasMessageContaining(ContaRazao.LIQUIDACAO_PIX.toString());
            assertThat(saldo(recebedor)).isEqualTo(Dinheiro.zero(Dinheiro.REAL));
        }

        @Test
        @DisplayName("recebedor com a conta encerrada: o crédito é recusado (é o que dispara o estorno na Saga)")
        void recebedorEncerrado() {
            UUID pagador = novaContaCom("100.00");
            UUID recebedor = novaConta();
            contas.alterarSituacao(recebedor, SituacaoConta.ENCERRADA);
            enviar(pagador, "30.00");

            assertThatThrownBy(() -> receber(recebedor, "30.00")).isInstanceOf(ContaInativaException.class);
            assertThat(saldo(ContaRazao.LIQUIDACAO_PIX)).isEqualTo(reais("30.00"));
        }

        @Test
        @DisplayName("as contas internas não são movimentadas, abertas nem bloqueadas pela API")
        void contasInternas() {
            UUID cliente = novaContaCom("10.00");
            UUID liquidacao = ContaRazao.LIQUIDACAO_PIX;
            assertThatThrownBy(() -> enviar(liquidacao, "1.00")).isInstanceOf(OperacaoNaoPermitidaException.class);
            assertThatThrownBy(() -> receber(ContaRazao.CAIXA, "1.00")).isInstanceOf(OperacaoNaoPermitidaException.class);
            assertThatThrownBy(() -> lancamentos.depositar(new Comandos.Deposito(chave(), liquidacao, reais("1"), null)))
                    .isInstanceOf(OperacaoNaoPermitidaException.class);
            assertThatThrownBy(() -> lancamentos.transferir(
                            new Comandos.Transferencia(chave(), cliente, liquidacao, reais("1"), null)))
                    .isInstanceOf(OperacaoNaoPermitidaException.class);
            assertThatThrownBy(() -> contas.abrir(liquidacao, Dinheiro.REAL)).isInstanceOf(OperacaoNaoPermitidaException.class);
            assertThatThrownBy(() -> contas.alterarSituacao(liquidacao, SituacaoConta.BLOQUEADA))
                    .isInstanceOf(OperacaoNaoPermitidaException.class);
        }
    }
}
