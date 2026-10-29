package io.github.alanjoffre.ledgerly.pix.integracao;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.alanjoffre.ledgerly.pix.aplicacao.OrquestradorDaSaga;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O pedido HTTP e o agendador de retomada ({@code RetomadaDasSagas}) não disputam a mesma Saga
 * (ADR-0031). Quem cria o Pix (ou decide a devolução) fica com a reserva do começo ao fim; o
 * agendador só pega a Saga que parou sem resposta do razão, ou cuja execução morreu.
 *
 * <p>Antes da correção, a reserva era solta entre um passo e outro: se o agendador passasse
 * naquele instante, levava a Saga, e o pedido respondia 202 ("o razão não respondeu") para um Pix
 * que o razão tinha respondido. No CI, com pouca CPU, isso aconteceu (28/10/2026). Aqui o agendador
 * roda sem pausa, num laço, para a janela aparecer sempre que existir.
 */
@QuarkusTest
@WithTestResource(ServicosFalsos.class)
@DisplayName("Saga do Pix com o agendador rodando ao mesmo tempo")
class SagaEAgendadorIT extends TesteDeIntegracao {

    private static final int VEZES = 60;

    @Inject
    OrquestradorDaSaga orquestrador;

    /** Roda o teste com um "agendador sem pausa": retoma as Sagas pendentes num laço, sem parar. */
    private <T> T comAgendadorSemPausa(Callable<T> teste) throws Exception {
        var parar = new AtomicBoolean();
        Thread agendador = Thread.ofVirtual().start(() -> {
            while (!parar.get()) {
                orquestrador.retomarPendentes();
            }
        });
        try {
            return teste.call();
        } finally {
            parar.set(true);
            agendador.join();
        }
    }

    @Test
    @DisplayName("Pix novo: o pedido leva a Saga até o fim — 201 CONCLUIDA sempre, nunca um 202 falso")
    void pixNovo() throws Exception {
        Conta joao = novaConta("João", "1000.00");
        String chave = chaveEmail(novaConta("Maria", "0.00"));

        List<String> respostas = comAgendadorSemPausa(() -> {
            List<String> lista = new ArrayList<>();
            for (int i = 0; i < VEZES; i++) {
                var resposta = pix(chave(), joao.id(), chave, "1.00").extract();
                lista.add(resposta.statusCode() + " " + resposta.path("situacao"));
            }
            return lista;
        });

        assertThat(respostas).hasSize(VEZES).containsOnly("201 CONCLUIDA");
    }

    @Test
    @DisplayName("Pix de três passos (termina em análise) e a devolução: o pedido não perde a vez")
    void emAnaliseEDevolucao() throws Exception {
        Conta joao = novaConta("João", "1000.00");
        Conta maria = novaConta("Maria", "0.00");
        String chave = chaveEmail(maria);
        Conta poupanca = novaConta("João", "0.00", joao.clienteId());
        // Crédito e estorno recusados: débito → crédito → estorno, e o Pix fica EM_ANALISE.
        ServicosFalsos.RAZAO.encerrar(maria.id());
        ServicosFalsos.RAZAO.encerrar(joao.id());

        List<String> respostas = comAgendadorSemPausa(() -> {
            List<String> lista = new ArrayList<>();
            for (int i = 0; i < VEZES; i++) {
                var criado = pix(chave(), joao.id(), chave, "1.00").extract();
                lista.add("pix " + criado.statusCode() + " " + criado.path("situacao"));
                var devolvido = given().header("Authorization", TokensPix.analista())
                        .contentType(ContentType.JSON)
                        .body(Map.of("contaDestinoId", poupanca.id().toString(),
                                "justificativa", "Conta de origem encerrada; devolver na poupança."))
                        .when().post("/v1/transferencias/{id}/devolucao", criado.<String>path("id"))
                        .then().extract();
                lista.add("devolução " + devolvido.statusCode() + " " + devolvido.path("situacao"));
            }
            return lista;
        });

        assertThat(respostas).hasSize(2 * VEZES).containsOnly("pix 201 EM_ANALISE", "devolução 200 ESTORNADA");
        assertThat(ServicosFalsos.RAZAO.saldo(poupanca.id())).isEqualByComparingTo("60.00");
        assertThat(ServicosFalsos.RAZAO.liquidacao()).isEqualByComparingTo("0");
    }
}
