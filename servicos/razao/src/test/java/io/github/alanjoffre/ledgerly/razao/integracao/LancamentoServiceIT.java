package io.github.alanjoffre.ledgerly.razao.integracao;

import static io.github.alanjoffre.ledgerly.nucleo.Dinheiro.reais;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import io.github.alanjoffre.ledgerly.razao.aplicacao.Comandos;
import io.github.alanjoffre.ledgerly.razao.aplicacao.ResultadoLancamento;
import io.github.alanjoffre.ledgerly.razao.dominio.ChaveIdempotencia;
import io.github.alanjoffre.ledgerly.razao.dominio.ChaveIdempotenciaReutilizadaException;
import io.github.alanjoffre.ledgerly.razao.dominio.ContaInativaException;
import io.github.alanjoffre.ledgerly.razao.dominio.ContaJaExisteException;
import io.github.alanjoffre.ledgerly.razao.dominio.ContaNaoEncontradaException;
import io.github.alanjoffre.ledgerly.razao.dominio.ContaRazao;
import io.github.alanjoffre.ledgerly.razao.dominio.EncerramentoComSaldoException;
import io.github.alanjoffre.ledgerly.razao.dominio.OperacaoNaoPermitidaException;
import io.github.alanjoffre.ledgerly.razao.dominio.SaldoInsuficienteException;
import io.github.alanjoffre.ledgerly.razao.dominio.SituacaoConta;
import io.github.alanjoffre.ledgerly.razao.dominio.TipoOperacao;
import io.github.alanjoffre.ledgerly.razao.dominio.TipoPartida;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Lançamentos com PostgreSQL de verdade")
class LancamentoServiceIT extends TesteDeIntegracao {

    /** Depois de CADA teste, o razão inteiro tem de continuar íntegro. */
    @AfterEach
    void razaoContinuaIntegro() {
        assertThat(contas.verificarIntegridade().integro()).isTrue();
    }

    @Nested
    @DisplayName("depósito, saque e transferência")
    class Movimentos {

        @Test
        @DisplayName("depósito credita o cliente e debita o Caixa")
        void deposito() {
            UUID conta = novaConta();
            ResultadoLancamento r = lancamentos.depositar(
                    new Comandos.Deposito(chave(), conta, reais("150.00"), "depósito em dinheiro"));

            assertThat(r.repetido()).isFalse();
            assertThat(saldo(conta)).isEqualTo(reais("150.00"));
            assertThat(saldo(ContaRazao.CAIXA)).isEqualTo(reais("150.00"));
            assertThat(r.lancamento().partidaDa(conta).tipo()).isEqualTo(TipoPartida.CREDITO);
            assertThat(lancamentos.consultar(r.lancamento().id())).isEqualTo(r.lancamento());
        }

        @Test
        @DisplayName("saque debita o cliente e credita o Caixa")
        void saque() {
            UUID conta = novaContaCom("100.00");
            lancamentos.sacar(new Comandos.Saque(chave(), conta, reais("40.00"), null));
            assertThat(saldo(conta)).isEqualTo(reais("60.00"));
            assertThat(saldo(ContaRazao.CAIXA)).isEqualTo(reais("60.00"));
        }

        @Test
        @DisplayName("transferência move o dinheiro entre clientes sem tocar no Caixa")
        void transferencia() {
            UUID origem = novaContaCom("100.00");
            UUID destino = novaConta();
            lancamentos.transferir(new Comandos.Transferencia(chave(), origem, destino, reais("25.50"), "pizza"));
            assertThat(saldo(origem)).isEqualTo(reais("74.50"));
            assertThat(saldo(destino)).isEqualTo(reais("25.50"));
            assertThat(saldo(ContaRazao.CAIXA)).isEqualTo(reais("100.00"));
        }

        @Test
        @DisplayName("saldo insuficiente: nada é gravado (nem lançamento, nem saldo)")
        void saldoInsuficienteNaoGravaNada() {
            UUID origem = novaContaCom("10.00");
            UUID destino = novaConta();
            long antes = jdbc.sql("SELECT count(*) FROM lancamento").query(Long.class).single();

            assertThatThrownBy(() -> lancamentos.transferir(
                            new Comandos.Transferencia(chave(), origem, destino, reais("10.01"), null)))
                    .isInstanceOf(SaldoInsuficienteException.class);

            assertThat(saldo(origem)).isEqualTo(reais("10.00"));
            assertThat(saldo(destino).ehZero()).isTrue();
            assertThat(jdbc.sql("SELECT count(*) FROM lancamento").query(Long.class).single()).isEqualTo(antes);
        }

        @Test
        @DisplayName("conta inexistente: ContaNaoEncontrada")
        void contaInexistente() {
            assertThatThrownBy(() -> lancamentos.depositar(
                            new Comandos.Deposito(chave(), UUID.randomUUID(), reais("1"), null)))
                    .isInstanceOf(ContaNaoEncontradaException.class);
            assertThatThrownBy(() -> lancamentos.consultar(UUID.randomUUID()))
                    .isInstanceOf(ContaNaoEncontradaException.class);
        }

        @Test
        @DisplayName("transferência para a própria conta: recusada")
        void mesmaConta() {
            UUID conta = novaContaCom("10.00");
            assertThatThrownBy(() -> lancamentos.transferir(
                            new Comandos.Transferencia(chave(), conta, conta, reais("1"), null)))
                    .isInstanceOf(OperacaoNaoPermitidaException.class);
        }

        @Test
        @DisplayName("a conta interna Caixa não é movimentada pela API")
        void caixaProtegido() {
            UUID conta = novaConta();
            assertThatThrownBy(() -> lancamentos.transferir(
                            new Comandos.Transferencia(chave(), ContaRazao.CAIXA, conta, reais("1000000"), null)))
                    .isInstanceOf(OperacaoNaoPermitidaException.class);
            assertThatThrownBy(() -> lancamentos.sacar(new Comandos.Saque(chave(), ContaRazao.CAIXA, reais("1"), null)))
                    .isInstanceOf(OperacaoNaoPermitidaException.class);
            assertThatThrownBy(() -> contas.alterarSituacao(ContaRazao.CAIXA, SituacaoConta.BLOQUEADA))
                    .isInstanceOf(OperacaoNaoPermitidaException.class);
        }
    }

    @Nested
    @DisplayName("idempotência")
    class Idempotencia {

        @Test
        @DisplayName("o mesmo pedido duas vezes: um lançamento só, e a segunda resposta diz que é repetida")
        void mesmoPedido() {
            UUID conta = novaConta();
            ChaveIdempotencia chave = chave();
            var pedido = new Comandos.Deposito(chave, conta, reais("50.00"), null);

            ResultadoLancamento primeira = lancamentos.depositar(pedido);
            ResultadoLancamento segunda = lancamentos.depositar(pedido);

            assertThat(segunda.repetido()).isTrue();
            assertThat(segunda.lancamento().id()).isEqualTo(primeira.lancamento().id());
            assertThat(saldo(conta)).isEqualTo(reais("50.00"));
        }

        @Test
        @DisplayName("a mesma chave num pedido diferente: erro, e nada é movido")
        void pedidoDiferente() {
            UUID conta = novaConta();
            ChaveIdempotencia chave = chave();
            lancamentos.depositar(new Comandos.Deposito(chave, conta, reais("50.00"), null));

            assertThatThrownBy(() -> lancamentos.depositar(new Comandos.Deposito(chave, conta, reais("51.00"), null)))
                    .isInstanceOf(ChaveIdempotenciaReutilizadaException.class);
            assertThat(saldo(conta)).isEqualTo(reais("50.00"));
        }

        @Test
        @DisplayName("a mesma chave num depósito em outra conta: erro")
        void mesmaChaveOutrasContas() {
            ChaveIdempotencia chave = chave();
            lancamentos.depositar(new Comandos.Deposito(chave, novaConta(), reais("1"), null));
            UUID outra = novaConta();
            assertThatThrownBy(() -> lancamentos.depositar(new Comandos.Deposito(chave, outra, reais("1"), null)))
                    .isInstanceOf(ChaveIdempotenciaReutilizadaException.class);
        }

        @Test
        @DisplayName("um pedido recusado (sem saldo) não queima a chave: dá para tentar de novo depois")
        void pedidoRecusadoNaoQueimaChave() {
            UUID conta = novaConta();
            ChaveIdempotencia chave = chave();
            var saque = new Comandos.Saque(chave, conta, reais("10.00"), null);
            assertThatThrownBy(() -> lancamentos.sacar(saque)).isInstanceOf(SaldoInsuficienteException.class);

            lancamentos.depositar(new Comandos.Deposito(chave(), conta, reais("10.00"), null));
            assertThat(lancamentos.sacar(saque).repetido()).isFalse();
            assertThat(saldo(conta).ehZero()).isTrue();
        }
    }

    @Nested
    @DisplayName("contas")
    class Contas {

        @Test
        @DisplayName("abrir é idempotente: a segunda vez devolve a mesma conta")
        void abrirIdempotente() {
            UUID id = UUID.randomUUID();
            assertThat(contas.abrir(id, Dinheiro.REAL).criada()).isTrue();
            assertThat(contas.abrir(id, Dinheiro.REAL).criada()).isFalse();
        }

        @Test
        @DisplayName("abrir de novo com outra moeda: conflito")
        void abrirOutraMoeda() {
            UUID id = novaConta();
            assertThatThrownBy(() -> contas.abrir(id, Currency.getInstance("USD")))
                    .isInstanceOf(ContaJaExisteException.class);
        }

        @Test
        @DisplayName("bloqueada recebe, mas não paga; desbloqueada volta a pagar")
        void bloqueio() {
            UUID conta = novaContaCom("100.00");
            contas.alterarSituacao(conta, SituacaoConta.BLOQUEADA);

            lancamentos.depositar(new Comandos.Deposito(chave(), conta, reais("1.00"), null));
            assertThatThrownBy(() -> lancamentos.sacar(new Comandos.Saque(chave(), conta, reais("1.00"), null)))
                    .isInstanceOf(ContaInativaException.class);

            contas.alterarSituacao(conta, SituacaoConta.ATIVA);
            lancamentos.sacar(new Comandos.Saque(chave(), conta, reais("1.00"), null));
            assertThat(saldo(conta)).isEqualTo(reais("100.00"));
        }

        @Test
        @DisplayName("encerrar com saldo: recusado; com saldo zero: encerra")
        void encerramento() {
            UUID conta = novaContaCom("5.00");
            assertThatThrownBy(() -> contas.alterarSituacao(conta, SituacaoConta.ENCERRADA))
                    .isInstanceOf(EncerramentoComSaldoException.class);

            lancamentos.sacar(new Comandos.Saque(chave(), conta, reais("5.00"), null));
            assertThat(contas.alterarSituacao(conta, SituacaoConta.ENCERRADA).situacao())
                    .isEqualTo(SituacaoConta.ENCERRADA);
            assertThat(contas.consultar(conta).situacao()).isEqualTo(SituacaoConta.ENCERRADA);
        }

        @Test
        @DisplayName("alterar situação de conta inexistente: ContaNaoEncontrada")
        void situacaoContaInexistente() {
            assertThatThrownBy(() -> contas.alterarSituacao(UUID.randomUUID(), SituacaoConta.BLOQUEADA))
                    .isInstanceOf(ContaNaoEncontradaException.class);
        }

        @Test
        @DisplayName("extrato: da partida mais nova para a mais antiga, com o saldo após cada uma")
        void extrato() {
            UUID conta = novaContaCom("100.00");
            lancamentos.sacar(new Comandos.Saque(chave(), conta, reais("30.00"), "saque"));

            var itens = contas.extrato(conta, 10);

            assertThat(itens).hasSize(2);
            assertThat(itens.getFirst().operacao()).isEqualTo(TipoOperacao.SAQUE);
            assertThat(itens.getFirst().saldoApos()).isEqualTo(reais("70.00"));
            assertThat(itens.getLast().saldoApos()).isEqualTo(reais("100.00"));
            assertThat(contas.extrato(conta, 1)).hasSize(1);
        }

        @Test
        @DisplayName("extrato: limite fora de 1..500 é recusado")
        void extratoLimite() {
            UUID conta = novaConta();
            assertThatThrownBy(() -> contas.extrato(conta, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> contas.extrato(conta, 501)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
