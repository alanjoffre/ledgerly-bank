package io.github.alanjoffre.ledgerly.pix.integracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.Antifraude;
import io.github.alanjoffre.ledgerly.pix.dominio.ResultadoDaAvaliacao;
import io.github.alanjoffre.ledgerly.pix.integracao.ServicosFalsos.AntifraudeFalso;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.faulttolerance.api.CircuitBreakerState;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O circuit breaker da chamada ao antifraude (ADR-0041): com o antifraude fora, o Pix para de esperar
 * por ele e vai direto para a falha segura, que continua a mesma (ADR-0033). Nos testes, o circuito
 * tenta de novo em 2 s ({@link ServicosFalsos}).
 */
@QuarkusTest
@WithTestResource(ServicosFalsos.class)
@DisplayName("Circuit breaker do antifraude")
class CircuitoDoAntifraudeIT extends TesteDeIntegracao {

    private static final AntifraudeFalso ANTIFRAUDE = ServicosFalsos.ANTIFRAUDE;
    private static final int JANELA = 20;

    @Inject
    Antifraude antifraude;

    private static Antifraude.Pedido pedido() {
        return new Antifraude.Pedido(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Dinheiro.reais("10.00"), Instant.now(), Instant.now());
    }

    private CircuitBreakerState estado() {
        return circuitos.currentState("antifraude");
    }

    /** Todas ao mesmo tempo: com o antifraude lento, em fila levariam 20 × 800 ms. */
    private List<ResultadoDaAvaliacao> avaliarJuntas(int quantas) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ResultadoDaAvaliacao>> chamadas = new ArrayList<>();
            for (int i = 0; i < quantas; i++) {
                chamadas.add(executor.submit(() -> antifraude.avaliar(pedido())));
            }
            List<ResultadoDaAvaliacao> resultados = new ArrayList<>();
            for (var chamada : chamadas) {
                resultados.add(chamada.get());
            }
            return resultados;
        }
    }

    private void abrir() {
        ANTIFRAUDE.foraDoAr(true);
        for (int i = 0; i < JANELA; i++) {
            antifraude.avaliar(pedido());
        }
        assertThat(estado()).isEqualTo(CircuitBreakerState.OPEN);
    }

    @Test
    @DisplayName("fora do ar: abre depois de 20 chamadas, e a seguinte nem sai do Pix")
    void abreComOForaDoAr() {
        ANTIFRAUDE.foraDoAr(true);
        for (int i = 0; i < JANELA - 1; i++) {
            assertThat(antifraude.avaliar(pedido()))
                    .isEqualTo(new ResultadoDaAvaliacao.SemResposta("HTTP 503"));
        }
        assertThat(estado()).as("com 19 chamadas, a janela ainda não encheu").isEqualTo(CircuitBreakerState.CLOSED);

        antifraude.avaliar(pedido());
        assertThat(estado()).isEqualTo(CircuitBreakerState.OPEN);
        int pedidos = ANTIFRAUDE.pedidos().size();

        assertThat(antifraude.avaliar(pedido())).isEqualTo(new ResultadoDaAvaliacao.SemResposta("circuito aberto"));
        assertThat(ANTIFRAUDE.pedidos()).hasSize(pedidos);
    }

    @Test
    @DisplayName("lento: o tempo esgotado também conta, e com o circuito aberto a resposta é imediata")
    void abreComALentidao() throws Exception {
        ANTIFRAUDE.demorar(1500);

        assertThat(avaliarJuntas(JANELA)).allSatisfy(r -> assertThat(r).isInstanceOf(ResultadoDaAvaliacao.SemResposta.class));
        assertThat(estado()).isEqualTo(CircuitBreakerState.OPEN);

        long inicio = System.nanoTime();
        assertThat(antifraude.avaliar(pedido())).isEqualTo(new ResultadoDaAvaliacao.SemResposta("circuito aberto"));
        assertThat((System.nanoTime() - inicio) / 1_000_000).as("sem esperar os 800 ms").isLessThan(200);
    }

    @Test
    @DisplayName("com o circuito aberto, a falha segura é a mesma: até R$ 500,00 segue, acima fica retido")
    void falhaSeguraIgual() {
        Conta joao = novaConta("João", "10000.00");
        Conta maria = novaConta("Maria", "0.00");
        String chaveDaMaria = chaveEmail(maria);
        abrir();
        int pedidos = ANTIFRAUDE.pedidos().size();

        pix(chave(), joao.id(), chaveDaMaria, "500.00")
                .statusCode(201)
                .body("situacao", equalTo("CONCLUIDA"))
                .body("notaDeRisco", nullValue());
        pix(chave(), joao.id(), chaveDaMaria, "500.01")
                .statusCode(202)
                .body("situacao", equalTo("RETIDA"))
                .body("notaDeRisco", nullValue());

        assertThat(ANTIFRAUDE.pedidos()).as("nenhum dos dois perguntou ao antifraude").hasSize(pedidos);
        assertThat(ServicosFalsos.RAZAO.saldo(maria.id())).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("o antifraude voltou: depois da espera, 3 chamadas certas fecham o circuito")
    void fechaQuandoVolta() {
        abrir();
        ANTIFRAUDE.foraDoAr(false);
        ANTIFRAUDE.decidir("APROVAR", 37);

        Awaitility.await().pollInterval(Duration.ofMillis(100))
                .until(() -> antifraude.avaliar(pedido()) instanceof ResultadoDaAvaliacao.Aprovar);
        assertThat(estado()).isEqualTo(CircuitBreakerState.HALF_OPEN);

        antifraude.avaliar(pedido());
        antifraude.avaliar(pedido());
        assertThat(estado()).isEqualTo(CircuitBreakerState.CLOSED);
    }

    @Test
    @DisplayName("a chamada de teste falhou: o circuito abre de novo")
    void reabreSeAindaEstaFora() {
        abrir();

        Awaitility.await().pollInterval(Duration.ofMillis(100))
                .until(() -> antifraude.avaliar(pedido()).equals(new ResultadoDaAvaliacao.SemResposta("HTTP 503")));

        assertThat(estado()).isEqualTo(CircuitBreakerState.OPEN);
    }

    @Test
    @DisplayName("4xx não abre: o antifraude está atendendo, e abrir o circuito não pouparia espera")
    void quatroXxNaoAbre() {
        ANTIFRAUDE.responderStatus(422);

        for (int i = 0; i < JANELA + 5; i++) {
            assertThat(antifraude.avaliar(pedido())).isEqualTo(new ResultadoDaAvaliacao.SemResposta("HTTP 422"));
        }

        assertThat(estado()).isEqualTo(CircuitBreakerState.CLOSED);
    }
}
