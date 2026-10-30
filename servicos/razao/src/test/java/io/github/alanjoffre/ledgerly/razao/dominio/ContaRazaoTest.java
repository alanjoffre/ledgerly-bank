package io.github.alanjoffre.ledgerly.razao.dominio;

import static io.github.alanjoffre.ledgerly.nucleo.Dinheiro.reais;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ContaRazao")
class ContaRazaoTest {

    private static final Currency DOLAR = Currency.getInstance("USD");

    private static ContaRazao contaDeClienteCom(String saldo) {
        ContaRazao conta = ContaRazao.abrirContaDeCliente(UUID.randomUUID(), Dinheiro.REAL);
        if (!reais(saldo).ehZero()) {
            conta.aplicar(TipoPartida.CREDITO, reais(saldo));
        }
        return conta;
    }

    private static ContaRazao caixa(String saldo) {
        return ContaRazao.reconstituir(
                ContaRazao.CAIXA, Natureza.DEVEDORA, Dinheiro.REAL, false, reais(saldo), SituacaoConta.ATIVA);
    }

    @Nested
    @DisplayName("natureza contábil")
    class NaturezaContabil {

        @Test
        @DisplayName("conta de cliente (credora): crédito aumenta, débito diminui")
        void credora() {
            ContaRazao conta = contaDeClienteCom("100.00");
            assertThat(conta.aplicar(TipoPartida.CREDITO, reais("10.00"))).isEqualTo(reais("110.00"));
            assertThat(conta.aplicar(TipoPartida.DEBITO, reais("30.00"))).isEqualTo(reais("80.00"));
            assertThat(conta.saldo()).isEqualTo(reais("80.00"));
        }

        @Test
        @DisplayName("caixa (devedora): débito aumenta, crédito diminui")
        void devedora() {
            ContaRazao caixa = caixa("100.00");
            assertThat(caixa.aplicar(TipoPartida.DEBITO, reais("10.00"))).isEqualTo(reais("110.00"));
            assertThat(caixa.aplicar(TipoPartida.CREDITO, reais("30.00"))).isEqualTo(reais("80.00"));
        }

        @Test
        @DisplayName("conta nova de cliente: credora, ativa, saldo zero, sem saldo negativo")
        void contaNova() {
            ContaRazao conta = ContaRazao.abrirContaDeCliente(UUID.randomUUID(), Dinheiro.REAL);
            assertThat(conta.natureza()).isEqualTo(Natureza.CREDORA);
            assertThat(conta.situacao()).isEqualTo(SituacaoConta.ATIVA);
            assertThat(conta.saldo().ehZero()).isTrue();
            assertThat(conta.permiteSaldoNegativo()).isFalse();
            assertThat(conta.moeda()).isEqualTo(Dinheiro.REAL);
        }
    }

    @Nested
    @DisplayName("saldo")
    class Saldo {

        @Test
        @DisplayName("recusa débito maior que o saldo e não altera o saldo")
        void saldoInsuficiente() {
            ContaRazao conta = contaDeClienteCom("10.00");
            assertThatThrownBy(() -> conta.aplicar(TipoPartida.DEBITO, reais("10.01")))
                    .isInstanceOf(SaldoInsuficienteException.class)
                    .hasMessageContaining("saldo BRL 10.00, débito de BRL 10.01");
            assertThat(conta.saldo()).isEqualTo(reais("10.00"));
        }

        @Test
        @DisplayName("aceita débito exatamente igual ao saldo (zera a conta)")
        void zeraConta() {
            ContaRazao conta = contaDeClienteCom("10.00");
            assertThat(conta.aplicar(TipoPartida.DEBITO, reais("10.00")).ehZero()).isTrue();
        }

        @Test
        @DisplayName("conta que permite saldo negativo pode ficar negativa")
        void permiteNegativo() {
            ContaRazao conta = ContaRazao.reconstituir(
                    UUID.randomUUID(), Natureza.CREDORA, Dinheiro.REAL, true, reais("0"), SituacaoConta.ATIVA);
            assertThat(conta.aplicar(TipoPartida.DEBITO, reais("5.00"))).isEqualTo(reais("-5.00"));
        }

        @Test
        @DisplayName("recusa partida em moeda diferente da conta")
        void moedaDiferente() {
            ContaRazao conta = contaDeClienteCom("10.00");
            assertThatThrownBy(() -> conta.aplicar(TipoPartida.CREDITO, Dinheiro.de(BigDecimal.ONE, DOLAR)))
                    .isInstanceOf(LancamentoInvalidoException.class);
        }

        @Test
        @DisplayName("recusa reconstituir conta com saldo em outra moeda")
        void reconstituirMoedaErrada() {
            assertThatThrownBy(() -> ContaRazao.reconstituir(
                            UUID.randomUUID(), Natureza.CREDORA, Dinheiro.REAL, false,
                            Dinheiro.zero(DOLAR), SituacaoConta.ATIVA))
                    .isInstanceOf(LancamentoInvalidoException.class);
        }
    }

    @Nested
    @DisplayName("situação")
    class Situacao {

        @Test
        @DisplayName("bloqueada: recebe crédito, mas não sai dinheiro")
        void bloqueada() {
            ContaRazao conta = contaDeClienteCom("50.00");
            conta.alterarSituacao(SituacaoConta.BLOQUEADA);
            assertThat(conta.aplicar(TipoPartida.CREDITO, reais("10.00"))).isEqualTo(reais("60.00"));
            assertThatThrownBy(() -> conta.aplicar(TipoPartida.DEBITO, reais("1.00")))
                    .isInstanceOf(ContaInativaException.class)
                    .hasMessageContaining("bloqueada");
        }

        @Test
        @DisplayName("desbloquear volta a permitir saída")
        void desbloquear() {
            ContaRazao conta = contaDeClienteCom("50.00");
            conta.alterarSituacao(SituacaoConta.BLOQUEADA);
            conta.alterarSituacao(SituacaoConta.ATIVA);
            assertThat(conta.aplicar(TipoPartida.DEBITO, reais("1.00"))).isEqualTo(reais("49.00"));
        }

        @Test
        @DisplayName("encerrada: não movimenta em nenhum sentido")
        void encerrada() {
            ContaRazao conta = contaDeClienteCom("0");
            conta.alterarSituacao(SituacaoConta.ENCERRADA);
            assertThatThrownBy(() -> conta.aplicar(TipoPartida.CREDITO, reais("1.00")))
                    .isInstanceOf(ContaInativaException.class)
                    .hasMessageContaining("encerrada");
        }

        @Test
        @DisplayName("só encerra com saldo zero")
        void encerrarComSaldo() {
            ContaRazao conta = contaDeClienteCom("0.01");
            assertThatThrownBy(() -> conta.alterarSituacao(SituacaoConta.ENCERRADA))
                    .isInstanceOf(EncerramentoComSaldoException.class);
            assertThat(conta.situacao()).isEqualTo(SituacaoConta.ATIVA);
        }

        @Test
        @DisplayName("encerramento é definitivo, mas repetir o encerramento não é erro")
        void encerramentoDefinitivo() {
            ContaRazao conta = contaDeClienteCom("0");
            conta.alterarSituacao(SituacaoConta.ENCERRADA);
            conta.alterarSituacao(SituacaoConta.ENCERRADA);
            assertThatThrownBy(() -> conta.alterarSituacao(SituacaoConta.ATIVA))
                    .isInstanceOf(ContaInativaException.class);
        }
    }

    @Test
    @DisplayName("só a conta interna pode ficar negativa; a de cliente, nunca")
    void quemPodeFicarNegativa() {
        assertThat(contaDeClienteCom("0.00").permiteSaldoNegativo()).isFalse();
        assertThat(ContaRazao.reconstituir(ContaRazao.LIQUIDACAO_PIX, Natureza.DEVEDORA, Dinheiro.REAL, true,
                reais("0.00"), SituacaoConta.ATIVA).permiteSaldoNegativo()).isTrue();
    }
}
