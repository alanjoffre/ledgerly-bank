package io.github.alanjoffre.ledgerly.assistente.aplicacao;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.alanjoffre.ledgerly.assistente.dominio.Consulta;
import io.github.alanjoffre.ledgerly.assistente.dominio.Conta;
import io.github.alanjoffre.ledgerly.assistente.dominio.Lancamento;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Ferramentas do cliente")
class FerramentasDoClienteTest {

    private static final LocalDate HOJE = LocalDate.parse("2026-10-28");
    private final BancarioFalso bancario = new BancarioFalso();
    private final Cliente ana = new Cliente(UUID.randomUUID(), "token-da-ana");
    private Conta corrente;

    @BeforeEach
    void umaConta() {
        corrente = bancario.conta("12345-6", "1234.56");
    }

    private FerramentasDoCliente ferramentas() {
        return new FerramentasDoCliente(bancario, ana, HOJE);
    }

    private void lancar(String quando, boolean credito, String valor, String saldoApos, String descricao) {
        bancario.lancamentos.get(corrente.id()).addFirst(new Lancamento(
                Instant.parse(quando), credito, new BigDecimal(valor), new BigDecimal(saldoApos), descricao));
    }

    @Test
    @DisplayName("minhasContas mostra agência e número, nunca o id")
    void minhasContas() {
        String texto = ferramentas().minhasContas();
        assertThat(texto).isEqualTo("Contas do cliente:\n- agência 0001, conta 12345-6");
        assertThat(texto).doesNotContain(corrente.id().toString());
    }

    @Test
    @DisplayName("saldo em reais; sem número, vale a única conta; o número vale com ou sem traço")
    void saldo() {
        assertThat(ferramentas().saldo("12345-6")).isEqualTo("Saldo da conta 12345-6: R$ 1.234,56.");
        assertThat(ferramentas().saldo("")).isEqualTo("Saldo da conta 12345-6: R$ 1.234,56.");
        assertThat(ferramentas().saldo("123456")).isEqualTo("Saldo da conta 12345-6: R$ 1.234,56.");
    }

    @Test
    @DisplayName("conta que não é da pessoa: responde que não achou e não pergunta ao extrato")
    void contaDeOutro() {
        assertThat(ferramentas().saldo("99999-9"))
                .isEqualTo("Nenhuma conta do cliente tem o número 99999-9. As contas dele: 12345-6.");
        assertThat(ferramentas().lancamentos("99999-9", null, null)).startsWith("Nenhuma conta do cliente");
        assertThat(bancario.lidas).isEmpty();
    }

    @Test
    @DisplayName("com duas contas e sem número, pede o número")
    void duasContas() {
        bancario.conta("22222-2", "10.00");
        assertThat(ferramentas().saldo(null)).isEqualTo("Diga o número da conta: o cliente tem estas: 12345-6, 22222-2.");
        assertThat(bancario.lidas).isEmpty();
    }

    @Test
    @DisplayName("lançamentos do período, pelo dia de Brasília, com os totais já somados")
    void lancamentosDoPeriodo() {
        lancar("2026-10-20T15:00:00Z", true, "1500.00", "1500.00", "Depósito");
        lancar("2026-10-27T12:00:00Z", false, "80.00", "1420.00", "Pix enviado para Mercado Bom Preço");
        lancar("2026-10-28T02:30:00Z", false, "185.44", "1234.56", "Pix enviado para Farmácia Vida");
        // 28/10 02:30 em UTC é 27/10 23:30 em Brasília: entra em "dia 27", não em "dia 28".

        String texto = ferramentas().lancamentos("12345-6", "2026-10-27", "2026-10-27");

        assertThat(texto).isEqualTo("""
                Lançamentos da conta 12345-6 de 2026-10-27 a 2026-10-27 (2 lançamentos, os mais novos primeiro):
                - 2026-10-27 · saída · R$ 185,44 · Pix enviado para Farmácia Vida · saldo depois: R$ 1.234,56
                - 2026-10-27 · saída · R$ 80,00 · Pix enviado para Mercado Bom Preço · saldo depois: R$ 1.420,00
                Total de entradas: R$ 0,00. Total de saídas: R$ 265,44.""");
    }

    @Test
    @DisplayName("sem datas: os últimos 30 dias até hoje; período vazio diz \"nenhum\"")
    void semDatas() {
        lancar("2026-09-01T15:00:00Z", true, "10.00", "10.00", "Depósito");
        assertThat(ferramentas().lancamentos(null, null, null))
                .isEqualTo("Lançamentos da conta 12345-6 de 2026-09-28 a 2026-10-28: nenhum.");
    }

    @Test
    @DisplayName("data errada, período invertido e período longo demais")
    void datas() {
        assertThat(ferramentas().lancamentos(null, "27/10/2026", null)).isEqualTo("Data inválida: use AAAA-MM-DD (por exemplo 2026-10-28).");
        assertThat(ferramentas().lancamentos(null, "2026-10-28", "2026-10-01")).isEqualTo("O primeiro dia (2026-10-28) vem depois do último (2026-10-01).");
        assertThat(ferramentas().lancamentos(null, "2025-01-01", "2026-10-28")).startsWith("Lançamentos da conta 12345-6 de 2026-07-30 a 2026-10-28");
    }

    @Test
    @DisplayName("mais de 30 lançamentos: mostra 30, e os totais contam todos")
    void muitos() {
        for (int dia = 1; dia <= 31; dia++) {
            lancar("2026-10-%02dT15:00:00Z".formatted(Math.min(dia, 28)), false, "1.00", "0.00", "Tarifa de teste " + dia);
        }
        String texto = ferramentas().lancamentos(null, "2026-10-01", "2026-10-28");
        assertThat(texto).contains("(31 lançamentos").contains("(e mais 1 lançamentos mais antigos, que entram nos totais)")
                .endsWith("Total de saídas: R$ 31,00.");
    }

    @Test
    @DisplayName("extrato fora do ar: a ferramenta diz isso ao modelo, sem lançar")
    void foraDoAr() {
        bancario.foraDoAr = true;
        assertThat(ferramentas().saldo("12345-6")).isEqualTo(FerramentasDoCliente.FORA_DO_AR);
    }

    @Test
    @DisplayName("toda chamada fica em consultas(), na ordem: é a prova da trava de saída")
    void consultas() {
        FerramentasDoCliente f = ferramentas();
        f.minhasContas();
        f.saldo("12345-6");
        assertThat(f.consultas()).extracting(Consulta::ferramenta).containsExactly("minhasContas", "saldo");
        assertThat(f.consultas().get(1).resultado()).contains("R$ 1.234,56");
    }
}
