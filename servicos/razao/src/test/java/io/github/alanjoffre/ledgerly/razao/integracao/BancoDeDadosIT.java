package io.github.alanjoffre.ledgerly.razao.integracao;

import static io.github.alanjoffre.ledgerly.nucleo.Dinheiro.reais;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.alanjoffre.ledgerly.razao.aplicacao.Comandos;
import io.github.alanjoffre.ledgerly.razao.dominio.ContaRazao;
import io.github.alanjoffre.ledgerly.razao.dominio.ContasQuentes;
import io.github.alanjoffre.ledgerly.razao.dominio.Lancamento;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Defesa em profundidade: mesmo que a aplicação tivesse um erro e tentasse gravar algo errado,
 * o próprio PostgreSQL recusa. Estes testes falam SQL direto com o banco, sem passar pelo domínio.
 */
@DisplayName("Regras garantidas pelo próprio banco de dados")
class BancoDeDadosIT extends TesteDeIntegracao {

    @Autowired
    private TransactionTemplate transacao;

    @Test
    @DisplayName("lançamento não pode ser alterado (UPDATE recusado)")
    void lancamentoImutavel() {
        UUID conta = novaContaCom("10.00");
        assertThatThrownBy(() -> jdbc.sql("UPDATE lancamento SET descricao = 'adulterado'").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("O razão é imutável");
        assertThat(saldo(conta)).isEqualTo(reais("10.00"));
    }

    @Test
    @DisplayName("partida não pode ser apagada nem alterada")
    void partidaImutavel() {
        novaContaCom("10.00");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM partida").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("O razão é imutável");
        assertThatThrownBy(() -> jdbc.sql("UPDATE partida SET valor = 1000").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("O razão é imutável");
    }

    @Test
    @DisplayName("lançamento com débito sem crédito: recusado no COMMIT pelo gatilho de partidas dobradas")
    void partidasDobradasNoBanco() {
        UUID conta = novaConta();
        UUID lancamento = UUID.randomUUID();

        assertThatThrownBy(() -> transacao.executeWithoutResult(status -> {
                    jdbc.sql("INSERT INTO lancamento (id, chave_idempotencia, tipo, registrado_em) "
                                    + "VALUES (:id, 'manual', 'DEPOSITO', :agora)")
                            .param("id", lancamento)
                            .param("agora", Timestamp.from(Instant.now()))
                            .update();
                    jdbc.sql("INSERT INTO partida (lancamento_id, conta_id, tipo, valor, saldo_apos) "
                                    + "VALUES (:l, :c, 'CREDITO', 1000000, 1000000)")
                            .param("l", lancamento)
                            .param("c", conta)
                            .update();
                }))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("desbalanceado");

        assertThat(jdbc.sql("SELECT count(*) FROM lancamento WHERE id = :id")
                        .param("id", lancamento).query(Long.class).single())
                .isZero();
    }

    @Test
    @DisplayName("saldo negativo em conta de cliente: recusado pela restrição CHECK")
    void saldoNegativoNoBanco() {
        UUID conta = novaConta();
        assertThatThrownBy(() -> jdbc.sql("UPDATE conta_razao SET saldo = -0.01 WHERE id = :id")
                        .param("id", conta)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("saldo_nao_negativo");
    }

    @Test
    @DisplayName("a conferência de integridade detecta um saldo adulterado")
    void integridadeDetectaAdulteracao() {
        UUID conta = novaContaCom("10.00");
        lancamentos.sacar(new Comandos.Saque(chave(), conta, reais("3.00"), null));
        assertThat(contas.verificarIntegridade().integro()).isTrue();

        // Alguém altera o saldo direto no banco, por fora da aplicação:
        jdbc.sql("UPDATE conta_razao SET saldo = 1000 WHERE id = :id").param("id", conta).update();

        var relatorio = contas.verificarIntegridade();
        assertThat(relatorio.integro()).isFalse();
        assertThat(relatorio.contasComSaldoDivergente()).containsExactly(conta);
        assertThat(relatorio.balancos().getFirst().fecha()).isFalse();
    }

    @Test
    @DisplayName("sub-conta do Caixa pode ficar negativa; sub-conta da Liquidação Pix, não (ADR-0019)")
    void subcontasNoBanco() {
        UUID caixa3 = ContasQuentes.subconta(ContaRazao.CAIXA, 3);
        UUID liquidacao3 = ContasQuentes.subconta(ContaRazao.LIQUIDACAO_PIX, 3);

        assertThat(jdbc.sql("UPDATE conta_razao SET saldo = -5 WHERE id = :id").param("id", caixa3).update())
                .isEqualTo(1);
        assertThatThrownBy(() -> jdbc.sql("UPDATE conta_razao SET saldo = -5 WHERE id = :id")
                        .param("id", liquidacao3)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("saldo_nao_negativo");
        assertThat(jdbc.sql("SELECT count(*) FROM conta_razao WHERE id::text LIKE '00000000-0000-0000-0000-%'")
                        .query(Long.class).single())
                .isEqualTo(2L * ContasQuentes.MAXIMO_DE_SUBCONTAS);
    }

    @Test
    @DisplayName("uma segunda saída do mesmo envio de Pix: recusada pelo índice único, mesmo gravada por fora")
    void umaSaidaPorEnvioNoBanco() {
        UUID pagador = novaContaCom("100.00");
        UUID recebedor = novaConta();
        Lancamento envio = lancamentos.enviarPix(new Comandos.MovimentoPix(chave(), pagador, reais("30.00"), "Pix"))
                .lancamento();
        lancamentos.receberPix(new Comandos.SaidaDaLiquidacao(chave(), recebedor, reais("30.00"), "Pix", envio.id()));

        assertThatThrownBy(() -> jdbc.sql("INSERT INTO lancamento (id, chave_idempotencia, tipo, registrado_em, envio_pix_id) "
                                + "VALUES (:id, 'manual', 'PIX_ESTORNO', :agora, :envio)")
                        .param("id", UUID.randomUUID())
                        .param("agora", Timestamp.from(Instant.now()))
                        .param("envio", envio.id())
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uma_saida_por_envio");
    }

    @Test
    @DisplayName("referência a envio num lançamento que não sai da Liquidação Pix: recusada pela restrição CHECK")
    void envioSoNaSaidaNoBanco() {
        UUID pagador = novaContaCom("100.00");
        Lancamento envio = lancamentos.enviarPix(new Comandos.MovimentoPix(chave(), pagador, reais("30.00"), "Pix"))
                .lancamento();

        assertThatThrownBy(() -> jdbc.sql("INSERT INTO lancamento (id, chave_idempotencia, tipo, registrado_em, envio_pix_id) "
                                + "VALUES (:id, 'manual', 'DEPOSITO', :agora, :envio)")
                        .param("id", UUID.randomUUID())
                        .param("agora", Timestamp.from(Instant.now()))
                        .param("envio", envio.id())
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("envio_pix_so_na_saida");
    }
}
