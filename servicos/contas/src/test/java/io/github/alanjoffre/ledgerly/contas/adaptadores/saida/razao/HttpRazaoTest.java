package io.github.alanjoffre.ledgerly.contas.adaptadores.saida.razao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.alanjoffre.ledgerly.contas.aplicacao.RazaoIndisponivelException;
import io.github.alanjoffre.ledgerly.contas.dominio.EncerramentoComSaldoException;
import io.github.alanjoffre.ledgerly.contas.dominio.SituacaoConta;
import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.net.SocketTimeoutException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * O adaptador HTTP do razão contra um servidor simulado ({@link MockRestServiceServer}): confere o
 * pedido que sai e a tradução de cada resposta possível, sem subir o razão de verdade.
 */
@DisplayName("Adaptador HTTP do razão")
class HttpRazaoTest {

    private static final String BASE = "http://razao.teste";
    private static final UUID CONTA = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
    private final MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
    private final HttpRazao razao = new HttpRazao(builder.build());

    @AfterEach
    void todosOsPedidosEsperadosForamFeitos() {
        servidor.verify();
    }

    @Test
    @DisplayName("abrir conta: PUT /v1/contas/{id} com a moeda BRL")
    void abrirConta() {
        servidor.expect(requestTo(BASE + "/v1/contas/" + CONTA))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("{\"moeda\":\"BRL\"}"))
                .andRespond(withStatus(HttpStatus.CREATED));
        razao.abrirConta(CONTA);
    }

    @Test
    @DisplayName("consultar saldo: lê o texto \"10.50\" como Dinheiro exato")
    void consultarSaldo() {
        servidor.expect(requestTo(BASE + "/v1/contas/" + CONTA))
                .andRespond(withSuccess("""
                        {"id":"%s","natureza":"CREDORA","moeda":"BRL","saldo":"10.50","situacao":"ATIVA"}
                        """.formatted(CONTA), MediaType.APPLICATION_JSON));
        assertThat(razao.consultarSaldo(CONTA)).isEqualTo(Dinheiro.reais("10.50"));
    }

    @Test
    @DisplayName("consultar saldo sem corpo na resposta: erro de estado")
    void consultarSemCorpo() {
        servidor.expect(requestTo(BASE + "/v1/contas/" + CONTA)).andRespond(withSuccess());
        assertThatThrownBy(() -> razao.consultarSaldo(CONTA)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("encerrar com saldo: o 422 do razão vira EncerramentoComSaldoException")
    void encerramentoComSaldo() {
        servidor.expect(requestTo(BASE + "/v1/contas/" + CONTA + "/situacao"))
                .andExpect(content().json("{\"situacao\":\"ENCERRADA\"}"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("""
                                {"type":"urn:ledgerly:erro:encerramento-com-saldo","status":422,
                                 "detail":"A conta tem saldo BRL 5.00; só encerra com saldo zero"}
                                """));
        assertThatThrownBy(() -> razao.alterarSituacao(CONTA, SituacaoConta.ENCERRADA))
                .isInstanceOf(EncerramentoComSaldoException.class)
                .hasMessageContaining("BRL 5.00");
    }

    @Test
    @DisplayName("outro 4xx do razão: erro de programação (IllegalStateException)")
    void outro4xx() {
        servidor.expect(requestTo(BASE + "/v1/contas/" + CONTA + "/situacao"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"type\":\"urn:ledgerly:erro:requisicao-invalida\",\"status\":400}"));
        assertThatThrownBy(() -> razao.alterarSituacao(CONTA, SituacaoConta.BLOQUEADA))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("422 sem corpo de problema: erro de estado")
    void semProblema() {
        servidor.expect(requestTo(BASE + "/v1/contas/" + CONTA + "/situacao"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT));
        assertThatThrownBy(() -> razao.alterarSituacao(CONTA, SituacaoConta.BLOQUEADA))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("razão com erro 5xx: RazaoIndisponivelException")
    void erroInterno() {
        servidor.expect(requestTo(BASE + "/v1/contas/" + CONTA)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> razao.abrirConta(CONTA)).isInstanceOf(RazaoIndisponivelException.class);
    }

    @Test
    @DisplayName("tempo esgotado: RazaoIndisponivelException")
    void tempoEsgotado() {
        servidor.expect(requestTo(BASE + "/v1/contas/" + CONTA)).andRespond(withException(new SocketTimeoutException()));
        assertThatThrownBy(() -> razao.consultarSaldo(CONTA)).isInstanceOf(RazaoIndisponivelException.class);
    }
}
