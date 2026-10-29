package io.github.alanjoffre.ledgerly.extrato.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos.LancamentoRegistrado;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos.PartidaRegistrada;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.ProjecaoDoExtrato;
import io.github.alanjoffre.ledgerly.extrato.dominio.TipoOperacao;
import io.github.alanjoffre.ledgerly.extrato.dominio.TipoPartida;
import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import io.github.alanjoffre.ledgerly.seguranca.Papeis;
import io.github.alanjoffre.ledgerly.seguranca.teste.MockMvcComToken;
import io.github.alanjoffre.ledgerly.seguranca.teste.TokensDeTeste;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.context.WebApplicationContext;

/**
 * Quem lê o extrato: o papel (401/403) e o dono (404). O dono vem do contas; a conta de outro
 * cliente responde igual a uma conta sem extrato, sem ler o MongoDB (ADR-0026).
 */
@DisplayName("Autorização no extrato")
@AutoConfigureMockMvc
class AutorizacaoIT extends TesteDeIntegracao {

    @Autowired
    private WebApplicationContext contexto;

    @Autowired
    private ProjecaoDoExtrato projecao;

    @Autowired
    private DonosFalsos donos;

    private MockMvcTester http;
    private final UUID ana = UUID.randomUUID();
    private final UUID bruno = UUID.randomUUID();
    private UUID contaDaAna;
    private UUID contaDoBruno;

    @BeforeEach
    void duasContas() {
        http = MockMvcComToken.semPadrao(contexto);
        contaDaAna = contaCom("100.00");
        contaDoBruno = contaCom("999.00");
        donos.definir(contaDaAna, ana);
        donos.definir(contaDoBruno, bruno);
    }

    @AfterEach
    void religar() {
        donos.derrubar(false);
    }

    private UUID contaCom(String valor) {
        UUID conta = UUID.randomUUID();
        projecao.projetar(new LancamentoRegistrado(UUID.randomUUID(), TipoOperacao.DEPOSITO, null, Instant.EPOCH,
                List.of(new PartidaRegistrada(1, conta, false, TipoPartida.CREDITO, Dinheiro.reais(valor), Dinheiro.reais(valor)))));
        return conta;
    }

    private MvcTestResult get(String token, String uri, Object... variaveis) {
        var pedido = http.get().uri(uri, variaveis);
        return (token == null ? pedido : pedido.header(HttpHeaders.AUTHORIZATION, token)).exchange();
    }

    @Test
    @DisplayName("a Ana lê o extrato e o saldo da própria conta")
    void donoLe() {
        String token = TokensDeTeste.cliente(ana);
        assertThat(get(token, "/v1/contas/{id}/extrato", contaDaAna)).hasStatusOk()
                .bodyJson().extractingPath("$.saldo.saldo").isEqualTo("100.00");
        assertThat(get(token, "/v1/contas/{id}/saldo", contaDaAna)).hasStatusOk();
    }

    @Test
    @DisplayName("a conta do Bruno, para a Ana, é igual a uma conta que não existe (404, sem o saldo)")
    void contaDeOutroEhComoSeNaoExistisse() throws Exception {
        String token = TokensDeTeste.cliente(ana);
        MvcTestResult deOutro = get(token, "/v1/contas/{id}/extrato", contaDoBruno);
        UUID inexistente = UUID.randomUUID();
        donos.definir(inexistente, ana);
        MvcTestResult semExtrato = get(token, "/v1/contas/{id}/extrato", inexistente);
        assertThat(deOutro).hasStatus(404).bodyJson().extractingPath("$.type")
                .isEqualTo("urn:ledgerly:erro:conta-nao-encontrada");
        String corpoDeOutro = deOutro.getResponse().getContentAsString();
        assertThat(corpoDeOutro).doesNotContain("999");
        assertThat(corpoDeOutro.replace(contaDoBruno.toString(), "<id>"))
                .isEqualTo(semExtrato.getResponse().getContentAsString().replace(inexistente.toString(), "<id>"));
        assertThat(get(token, "/v1/contas/{id}/saldo", contaDoBruno)).hasStatus(404);
    }

    @Test
    @DisplayName("conta que o contas não conhece, ou token sem cliente_id: 404")
    void semVinculo() {
        assertThat(get(TokensDeTeste.cliente(ana), "/v1/contas/{id}/extrato", UUID.randomUUID())).hasStatus(404);
        String semClienteId = TokensDeTeste.token().papeis(Papeis.CLIENTE).bearer();
        assertThat(get(semClienteId, "/v1/contas/{id}/extrato", contaDaAna)).hasStatus(404);
    }

    @Test
    @DisplayName("cliente_id sem o papel cliente: 403")
    void claimSemPapel() {
        String token = TokensDeTeste.token().clienteId(ana).bearer();
        assertThat(get(token, "/v1/contas/{id}/extrato", contaDaAna)).hasStatus(403);
    }

    @Test
    @DisplayName("analista e serviço leem qualquer conta, mesmo com o contas fora do ar")
    void leTudo() {
        donos.derrubar(true);
        assertThat(get(TokensDeTeste.analista(), "/v1/contas/{id}/extrato", contaDoBruno)).hasStatusOk();
        assertThat(get(TokensDeTeste.servico(), "/v1/contas/{id}/saldo", contaDoBruno)).hasStatusOk();
    }

    @Test
    @DisplayName("contas fora do ar, para o cliente: 503 com Retry-After (não 404 — a conta pode ser dele)")
    void contasForaDoAr() {
        donos.derrubar(true);
        assertThat(get(TokensDeTeste.cliente(ana), "/v1/contas/{id}/extrato", contaDaAna)).hasStatus(503)
                .hasHeader(HttpHeaders.RETRY_AFTER, "5")
                .bodyJson().extractingPath("$.type").isEqualTo("urn:ledgerly:erro:contas-indisponivel");
    }

    @Test
    @DisplayName("sem token: 401; token vencido ou de outro emissor: 401")
    void semToken() {
        assertThat(get(null, "/v1/contas/{id}/extrato", contaDaAna)).hasStatus(401)
                .hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"ledgerly\"");
        assertThat(get(TokensDeTeste.token().papeis(Papeis.CLIENTE).clienteId(ana).vencido().bearer(),
                "/v1/contas/{id}/extrato", contaDaAna)).hasStatus(401);
        assertThat(get(TokensDeTeste.token().papeis(Papeis.CLIENTE).clienteId(ana).emissor("https://outro").bearer(),
                "/v1/contas/{id}/extrato", contaDaAna)).hasStatus(401);
    }

    @Test
    @DisplayName("o que não é extrato nem saldo é negado (negar por padrão)")
    void negadoPorPadrao() {
        assertThat(http.delete().uri("/v1/contas/{id}/extrato", contaDaAna)
                .header(HttpHeaders.AUTHORIZATION, TokensDeTeste.servico()).exchange()).hasStatus(403);
        assertThat(get(TokensDeTeste.servico(), "/v1/movimentacoes")).hasStatus(403);
    }

    @Test
    @DisplayName("health continua aberto, sem token")
    void healthAberto() {
        assertThat(get(null, "/actuator/health")).hasStatusOk();
    }
}
