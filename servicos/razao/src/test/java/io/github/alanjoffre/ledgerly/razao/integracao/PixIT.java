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
import io.github.alanjoffre.ledgerly.razao.dominio.EnvioPixInvalidoException;
import io.github.alanjoffre.ledgerly.razao.dominio.Lancamento;
import io.github.alanjoffre.ledgerly.razao.dominio.OperacaoNaoPermitidaException;
import io.github.alanjoffre.ledgerly.razao.dominio.SaldoInsuficienteException;
import io.github.alanjoffre.ledgerly.razao.dominio.SituacaoConta;
import io.github.alanjoffre.ledgerly.razao.dominio.TipoOperacao;
import io.github.alanjoffre.ledgerly.razao.dominio.TipoPartida;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    private Lancamento enviar(UUID conta, String valor) {
        return lancamentos.enviarPix(new Comandos.MovimentoPix(chave(), conta, reais(valor), "Pix")).lancamento();
    }

    private ResultadoLancamento receber(UUID conta, String valor, UUID envio) {
        return lancamentos.receberPix(new Comandos.SaidaDaLiquidacao(chave(), conta, reais(valor), "Pix", envio));
    }

    private ResultadoLancamento estornar(UUID conta, String valor, UUID envio) {
        return lancamentos.estornarPix(new Comandos.SaidaDaLiquidacao(chave(), conta, reais(valor), "Estorno", envio));
    }

    @Nested
    @DisplayName("o caminho do dinheiro")
    class Caminho {

        @Test
        @DisplayName("envio: sai do pagador e fica numa sub-conta da Liquidação Pix")
        void envio() {
            UUID pagador = novaContaCom("100.00");
            Lancamento envio = enviar(pagador, "30.00");

            assertThat(envio.tipo()).isEqualTo(TipoOperacao.PIX_ENVIO);
            assertThat(envio.partidaDa(pagador).tipo()).isEqualTo(TipoPartida.DEBITO);
            assertThat(envio.subcontaInterna(ContaRazao.LIQUIDACAO_PIX)).isPresent();
            assertThat(saldo(pagador)).isEqualTo(reais("70.00"));
            assertThat(saldo(ContaRazao.LIQUIDACAO_PIX)).isEqualTo(reais("30.00"));
        }

        @Test
        @DisplayName("recebimento: sai da MESMA sub-conta em que o envio entrou e chega ao recebedor")
        void recebimento() {
            UUID pagador = novaContaCom("100.00");
            UUID recebedor = novaConta();
            Lancamento envio = enviar(pagador, "30.00");
            ResultadoLancamento r = receber(recebedor, "30.00", envio.id());

            assertThat(r.lancamento().tipo()).isEqualTo(TipoOperacao.PIX_RECEBIMENTO);
            assertThat(r.lancamento().envioPixId()).isEqualTo(envio.id());
            assertThat(r.lancamento().subcontaInterna(ContaRazao.LIQUIDACAO_PIX))
                    .isEqualTo(envio.subcontaInterna(ContaRazao.LIQUIDACAO_PIX));
            assertThat(saldo(recebedor)).isEqualTo(reais("30.00"));
            assertThat(saldo(ContaRazao.LIQUIDACAO_PIX)).isEqualTo(Dinheiro.zero(Dinheiro.REAL));
        }

        @Test
        @DisplayName("estorno: o dinheiro volta ao pagador num lançamento novo; o envio continua lá")
        void estorno() {
            UUID pagador = novaContaCom("100.00");
            Lancamento envio = enviar(pagador, "30.00");
            ResultadoLancamento r = estornar(pagador, "30.00", envio.id());

            assertThat(r.lancamento().tipo()).isEqualTo(TipoOperacao.PIX_ESTORNO);
            assertThat(r.lancamento().id()).isNotEqualTo(envio.id());
            assertThat(lancamentos.consultar(envio.id()).tipo()).isEqualTo(TipoOperacao.PIX_ENVIO);
            assertThat(saldo(pagador)).isEqualTo(reais("100.00"));
            assertThat(saldo(ContaRazao.LIQUIDACAO_PIX)).isEqualTo(Dinheiro.zero(Dinheiro.REAL));
        }

        @Test
        @DisplayName("o mesmo passo repetido com a mesma chave não move o dinheiro duas vezes")
        void passoRepetido() {
            UUID pagador = novaContaCom("100.00");
            UUID recebedor = novaConta();
            var comando = new Comandos.MovimentoPix(
                    new ChaveIdempotencia("pix:1:envio"), pagador, reais("30.00"), "Pix");
            ResultadoLancamento primeiro = lancamentos.enviarPix(comando);
            ResultadoLancamento repetido = lancamentos.enviarPix(comando);

            assertThat(repetido.repetido()).isTrue();
            assertThat(repetido.lancamento()).isEqualTo(primeiro.lancamento());
            assertThat(saldo(pagador)).isEqualTo(reais("70.00"));

            var credito = new Comandos.SaidaDaLiquidacao(new ChaveIdempotencia("pix:1:recebimento"), recebedor,
                    reais("30.00"), "Pix", primeiro.lancamento().id());
            lancamentos.receberPix(credito);
            assertThat(lancamentos.receberPix(credito).repetido()).isTrue();
            assertThat(saldo(recebedor)).isEqualTo(reais("30.00"));
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
        @DisplayName("creditar um Pix que nunca saiu de ninguém: o envio apontado não existe, ou não é um envio")
        void recebimentoSemEnvio() {
            UUID recebedor = novaConta();
            UUID deposito = lancamentos.depositar(new Comandos.Deposito(chave(), recebedor, reais("5.00"), null))
                    .lancamento().id();

            assertThatThrownBy(() -> receber(recebedor, "1.00", UUID.randomUUID()))
                    .isInstanceOf(EnvioPixInvalidoException.class);
            assertThatThrownBy(() -> receber(recebedor, "5.00", deposito))
                    .isInstanceOf(EnvioPixInvalidoException.class)
                    .hasMessageContaining("não é um envio de Pix");
            assertThat(saldo(recebedor)).isEqualTo(reais("5.00"));
        }

        @Test
        @DisplayName("valor diferente do envio, ou estorno para quem não pagou: recusado")
        void saidaQueNaoBateComOEnvio() {
            UUID pagador = novaContaCom("100.00");
            UUID outro = novaConta();
            Lancamento envio = enviar(pagador, "30.00");

            assertThatThrownBy(() -> receber(outro, "30.01", envio.id()))
                    .isInstanceOf(EnvioPixInvalidoException.class)
                    .hasMessageContaining("foi de");
            assertThatThrownBy(() -> estornar(outro, "30.00", envio.id()))
                    .isInstanceOf(EnvioPixInvalidoException.class)
                    .hasMessageContaining("só pode voltar para a conta que pagou");
            assertThat(saldo(ContaRazao.LIQUIDACAO_PIX)).isEqualTo(reais("30.00"));
        }

        @Test
        @DisplayName("receber e também estornar o mesmo envio: o segundo é recusado, mesmo com dinheiro de outros Pix na Liquidação")
        void duasSaidasDoMesmoEnvio() {
            UUID pagador = novaContaCom("100.00");
            UUID recebedor = novaConta();
            Lancamento envio = enviar(pagador, "30.00");
            enviar(pagador, "30.00"); // outro Pix em andamento: a mesma sub-conta tem dinheiro de sobra
            receber(recebedor, "30.00", envio.id());

            assertThatThrownBy(() -> estornar(pagador, "30.00", envio.id()))
                    .isInstanceOf(EnvioPixInvalidoException.class)
                    .hasMessageContaining("já teve a sua saída");
            assertThatThrownBy(() -> receber(recebedor, "30.00", envio.id()))
                    .isInstanceOf(EnvioPixInvalidoException.class);
            assertThat(saldo(pagador)).isEqualTo(reais("40.00"));
            assertThat(saldo(ContaRazao.LIQUIDACAO_PIX)).isEqualTo(reais("30.00"));
        }

        @Test
        @DisplayName("recebimento e estorno do mesmo envio AO MESMO TEMPO: exatamente um passa")
        void duasSaidasSimultaneas() throws Exception {
            UUID pagador = novaContaCom("100.00");
            UUID recebedor = novaConta();
            Lancamento envio = enviar(pagador, "30.00");
            enviar(pagador, "30.00");

            var largada = new CountDownLatch(1);
            List<Callable<ResultadoLancamento>> pedidos = List.of(
                    () -> {
                        largada.await();
                        return receber(recebedor, "30.00", envio.id());
                    },
                    () -> {
                        largada.await();
                        return estornar(pagador, "30.00", envio.id());
                    });
            List<Future<ResultadoLancamento>> futuros = new ArrayList<>();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                pedidos.forEach(p -> futuros.add(executor.submit(p)));
                largada.countDown();
            }
            int aceitos = 0;
            for (Future<ResultadoLancamento> futuro : futuros) {
                try {
                    futuro.get();
                    aceitos++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(EnvioPixInvalidoException.class);
                }
            }
            assertThat(aceitos).isEqualTo(1);
            assertThat(saldo(ContaRazao.LIQUIDACAO_PIX)).isEqualTo(reais("30.00"));
            assertThat(saldo(pagador).somar(saldo(recebedor))).isEqualTo(reais("70.00"));
        }

        @Test
        @DisplayName("recebedor com a conta encerrada: o crédito é recusado (é o que dispara o estorno na Saga)")
        void recebedorEncerrado() {
            UUID pagador = novaContaCom("100.00");
            UUID recebedor = novaConta();
            contas.alterarSituacao(recebedor, SituacaoConta.ENCERRADA);
            Lancamento envio = enviar(pagador, "30.00");

            assertThatThrownBy(() -> receber(recebedor, "30.00", envio.id())).isInstanceOf(ContaInativaException.class);
            assertThat(saldo(ContaRazao.LIQUIDACAO_PIX)).isEqualTo(reais("30.00"));
        }

        @Test
        @DisplayName("as contas internas não são movimentadas, abertas nem bloqueadas pela API")
        void contasInternas() {
            UUID cliente = novaContaCom("10.00");
            UUID liquidacao = ContaRazao.LIQUIDACAO_PIX;
            assertThatThrownBy(() -> enviar(liquidacao, "1.00")).isInstanceOf(OperacaoNaoPermitidaException.class);
            assertThatThrownBy(() -> receber(ContaRazao.CAIXA, "1.00", UUID.randomUUID()))
                    .isInstanceOf(OperacaoNaoPermitidaException.class);
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
