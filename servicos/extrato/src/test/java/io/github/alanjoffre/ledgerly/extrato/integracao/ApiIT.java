package io.github.alanjoffre.ledgerly.extrato.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos.LancamentoRegistrado;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos.Pagador;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos.PartidaRegistrada;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos.PixConcluido;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos.Recebedor;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.ProjecaoDoExtrato;
import io.github.alanjoffre.ledgerly.extrato.dominio.TipoOperacao;
import io.github.alanjoffre.ledgerly.extrato.dominio.TipoPartida;
import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * A API REST de ponta a ponta: HTTP → controller → consulta → MongoDB. Os dados entram pela
 * projeção diretamente (sem o Kafka), para o teste ser sobre a API.
 */
@DisplayName("API REST do extrato")
@AutoConfigureMockMvc
class ApiIT extends TesteDeIntegracao {

    private static final Instant QUANDO = Instant.parse("2026-10-27T18:30:00.123Z");

    @Autowired
    private MockMvcTester http;

    @Autowired
    private ProjecaoDoExtrato projecao;

    /** Abre a conta com {@code quantos} depósitos de R$ 10,00; as sequências são 2, 4, 6... */
    private UUID contaComDepositos(int quantos) {
        UUID conta = UUID.randomUUID();
        for (int i = 1; i <= quantos; i++) {
            projecao.projetar(new LancamentoRegistrado(UUID.randomUUID(), TipoOperacao.DEPOSITO, "depósito " + i, QUANDO,
                    List.of(new PartidaRegistrada(2L * i, conta, false, TipoPartida.CREDITO,
                            Dinheiro.reais("10.00"), Dinheiro.reais(10 * i + ".00")))));
        }
        return conta;
    }

    @Test
    @DisplayName("GET extrato: saldo, linhas da mais nova para a mais antiga, dinheiro como texto")
    void extrato() {
        UUID conta = contaComDepositos(3);
        assertThat(http.get().uri("/v1/contas/{id}/extrato", conta)).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.saldo.saldo", v -> v.assertThat().isEqualTo("30.00"))
                .hasPathSatisfying("$.saldo.sequencia", v -> v.assertThat().isEqualTo(6))
                .hasPathSatisfying("$.itens.length()", v -> v.assertThat().isEqualTo(3))
                .hasPathSatisfying("$.itens[0].saldoApos", v -> v.assertThat().isEqualTo("30.00"))
                .hasPathSatisfying("$.itens[0].descricao", v -> v.assertThat().isEqualTo("Depósito"))
                .hasPathSatisfying("$.itens[0].mensagem", v -> v.assertThat().isEqualTo("depósito 3"))
                .hasPathSatisfying("$.itens[0].registradoEm", v -> v.assertThat().isEqualTo("2026-10-27T18:30:00.123Z"))
                .hasPathSatisfying("$.proximaPagina", v -> v.assertThat().isNull());
    }

    @Test
    @DisplayName("paginação por cursor: antesDe = proximaPagina, sem repetir nem pular linha")
    void paginacao() {
        UUID conta = contaComDepositos(5);
        assertThat(http.get().uri("/v1/contas/{id}/extrato?limite=2", conta)).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.itens[*].sequencia", v -> v.assertThat().asArray().containsExactly(10, 8))
                .hasPathSatisfying("$.proximaPagina", v -> v.assertThat().isEqualTo(8));
        assertThat(http.get().uri("/v1/contas/{id}/extrato?limite=2&antesDe=8", conta)).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.itens[*].sequencia", v -> v.assertThat().asArray().containsExactly(6, 4))
                .hasPathSatisfying("$.proximaPagina", v -> v.assertThat().isEqualTo(4));
        assertThat(http.get().uri("/v1/contas/{id}/extrato?limite=2&antesDe=4", conta)).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.itens[*].sequencia", v -> v.assertThat().asArray().containsExactly(2))
                .hasPathSatisfying("$.proximaPagina", v -> v.assertThat().isNull());
    }

    @Test
    @DisplayName("linha de Pix traz a contraparte e a chave mascarada")
    void pix() {
        UUID joao = contaComDepositos(1);
        UUID maria = UUID.randomUUID();
        UUID envio = UUID.randomUUID();
        UUID pix = UUID.randomUUID();
        projecao.projetar(new LancamentoRegistrado(envio, TipoOperacao.PIX_ENVIO, "Aluguel", QUANDO,
                List.of(new PartidaRegistrada(9, joao, false, TipoPartida.DEBITO, Dinheiro.reais("4.00"), Dinheiro.reais("6.00")))));
        projecao.projetar(new PixConcluido(pix, new Pagador(joao, "João da Silva"),
                new Recebedor(maria, "Maria Souza", "EMAIL", "m***@exemplo.com"), envio, UUID.randomUUID()));

        assertThat(http.get().uri("/v1/contas/{id}/extrato", joao)).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.itens[0].descricao", v -> v.assertThat().isEqualTo("Pix enviado para Maria Souza"))
                .hasPathSatisfying("$.itens[0].operacao", v -> v.assertThat().isEqualTo("PIX_ENVIO"))
                .hasPathSatisfying("$.itens[0].tipo", v -> v.assertThat().isEqualTo("DEBITO"))
                .hasPathSatisfying("$.itens[0].pix.pixId", v -> v.assertThat().isEqualTo(pix.toString()))
                .hasPathSatisfying("$.itens[0].pix.situacao", v -> v.assertThat().isEqualTo("CONCLUIDO"))
                .hasPathSatisfying("$.itens[0].pix.chave", v -> v.assertThat().isEqualTo("m***@exemplo.com"))
                .hasPathSatisfying("$.itens[1].pix", v -> v.assertThat().isNull());
    }

    @Test
    @DisplayName("GET saldo: saldo, moeda e até qual partida o extrato está atualizado")
    void saldo() {
        UUID conta = contaComDepositos(2);
        assertThat(http.get().uri("/v1/contas/{id}/saldo", conta)).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.contaId", v -> v.assertThat().isEqualTo(conta.toString()))
                .hasPathSatisfying("$.saldo", v -> v.assertThat().isEqualTo("20.00"))
                .hasPathSatisfying("$.moeda", v -> v.assertThat().isEqualTo("BRL"))
                .hasPathSatisfying("$.sequencia", v -> v.assertThat().isEqualTo(4))
                .hasPathSatisfying("$.atualizadoEm", v -> v.assertThat().isEqualTo("2026-10-27T18:30:00.123Z"));
    }

    @Test
    @DisplayName("conta sem nada no extrato: 404 com type estável, no extrato e no saldo")
    void inexistente() {
        UUID conta = UUID.randomUUID();
        assertThat(http.get().uri("/v1/contas/{id}/extrato", conta)).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                .hasPathSatisfying("$.type", v -> v.assertThat().isEqualTo("urn:ledgerly:erro:conta-nao-encontrada"));
        assertThat(http.get().uri("/v1/contas/{id}/saldo", conta)).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                .hasPathSatisfying("$.title", v -> v.assertThat().isEqualTo("Recurso não encontrado"));
    }

    @Test
    @DisplayName("limite fora de 1–500 e antesDe não positivo: 400")
    void parametrosForaDaFaixa() {
        UUID conta = contaComDepositos(1);
        for (String consulta : List.of("limite=0", "limite=501", "antesDe=0")) {
            assertThat(http.get().uri("/v1/contas/{id}/extrato?" + consulta, conta)).hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson().hasPathSatisfying("$.type", v -> v.assertThat().isEqualTo("urn:ledgerly:erro:requisicao-invalida"));
        }
    }

    @Test
    @DisplayName("id que não é UUID, ou limite que não é número: 400")
    void formatoInvalido() {
        assertThat(http.get().uri("/v1/contas/abc/extrato")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(http.get().uri("/v1/contas/{id}/extrato?limite=muitos", UUID.randomUUID()))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .hasPathSatisfying("$.detail", v -> v.assertThat().asString().contains("limite"));
    }

    @Test
    @DisplayName("a saúde do serviço responde em /actuator/health")
    void saude() {
        assertThat(http.get().uri("/actuator/health")).hasStatusOk();
    }
}
