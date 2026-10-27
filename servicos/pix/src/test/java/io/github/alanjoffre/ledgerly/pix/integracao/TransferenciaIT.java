package io.github.alanjoffre.ledgerly.pix.integracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.github.alanjoffre.ledgerly.pix.integracao.ServicosFalsos.RazaoFalso;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** A Saga do Pix de ponta a ponta: cada caminho, cada falha, e nenhum centavo duas vezes. */
@QuarkusTest
@WithTestResource(ServicosFalsos.class)
@DisplayName("Saga do Pix")
class TransferenciaIT extends TesteDeIntegracao {

    private static final RazaoFalso RAZAO = ServicosFalsos.RAZAO;
    private static final Duration PRAZO = Duration.ofSeconds(20);

    private void esperarSituacao(String id, String situacao) {
        await().atMost(PRAZO).untilAsserted(() -> consultar(id).body("situacao", equalTo(situacao)));
    }

    @Nested
    @DisplayName("caminhos")
    class Caminhos {

        @Test
        @DisplayName("feliz: 201 CONCLUIDA; o dinheiro passa pela Liquidação Pix e chega ao recebedor")
        void feliz() {
            Conta joao = novaConta("João da Silva", "500.00");
            Conta maria = novaConta("Maria Souza", "0.00");
            String chave = chaveEmail(maria);

            String id = pix(chave(), joao.id(), chave, "120.00")
                    .statusCode(201)
                    .header("Location", notNullValue())
                    .body("situacao", equalTo("CONCLUIDA"))
                    .body("valor", equalTo("120.00"))
                    .body("pagador.nome", equalTo("João da Silva"))
                    .body("recebedor.nome", equalTo("Maria Souza"))
                    .body("recebedor.chave", equalTo(chave.charAt(0) + "***@exemplo.com"))
                    .body("lancamentos.envio", notNullValue())
                    .body("lancamentos.recebimento", notNullValue())
                    .body("lancamentos.estorno", nullValue())
                    .extract().path("id");

            assertThat(RAZAO.saldo(joao.id())).isEqualByComparingTo("380.00");
            assertThat(RAZAO.saldo(maria.id())).isEqualByComparingTo("120.00");
            assertThat(RAZAO.liquidacao()).isEqualByComparingTo("0");
            assertThat(RAZAO.chamadas("pix:" + id + ":envio")).isEqualTo(1);
            assertThat(RAZAO.chamadas("pix:" + id + ":recebimento")).isEqualTo(1);
        }

        @Test
        @DisplayName("saldo insuficiente: 201 REJEITADA, nenhum dinheiro saiu e não houve crédito")
        void rejeitada() {
            Conta joao = novaConta("João", "10.00");
            Conta maria = novaConta("Maria", "0.00");

            String id = pix(chave(), joao.id(), chaveEmail(maria), "10.01")
                    .statusCode(201)
                    .body("situacao", equalTo("REJEITADA"))
                    .body("motivo", org.hamcrest.Matchers.containsString("Saldo insuficiente"))
                    .extract().path("id");

            assertThat(RAZAO.saldo(joao.id())).isEqualByComparingTo("10.00");
            assertThat(RAZAO.chamadas("pix:" + id + ":recebimento")).isZero();
        }

        @Test
        @DisplayName("recebedor encerrado: o crédito é recusado e a Saga compensa — 201 ESTORNADA, o pagador recebe de volta")
        void estornada() {
            Conta joao = novaConta("João", "300.00");
            Conta maria = novaConta("Maria", "0.00");
            String chave = chaveEmail(maria);
            RAZAO.encerrar(maria.id());

            pix(chave(), joao.id(), chave, "100.00")
                    .statusCode(201)
                    .body("situacao", equalTo("ESTORNADA"))
                    .body("motivo", org.hamcrest.Matchers.containsString("encerrada"))
                    .body("lancamentos.envio", notNullValue())
                    .body("lancamentos.recebimento", nullValue())
                    .body("lancamentos.estorno", notNullValue());

            assertThat(RAZAO.saldo(joao.id())).isEqualByComparingTo("300.00");
            assertThat(RAZAO.saldo(maria.id())).isEqualByComparingTo("0.00");
            assertThat(RAZAO.liquidacao()).isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("falhas no meio do caminho")
    class Falhas {

        @Test
        @DisplayName("razão fora do ar no crédito: 202, e a Saga termina sozinha quando ele volta")
        void razaoForaDoAr() {
            Conta joao = novaConta("João", "300.00");
            Conta maria = novaConta("Maria", "0.00");
            String chave = chaveEmail(maria);
            RAZAO.indisponivel("recebimentos", 2);

            String id = pix(chave(), joao.id(), chave, "50.00")
                    .statusCode(202)
                    .body("situacao", equalTo("DEBITADA"))
                    .body("tentativas", equalTo(1))
                    .body("proximaTentativaEm", notNullValue())
                    .extract().path("id");

            esperarSituacao(id, "CONCLUIDA");
            assertThat(RAZAO.chamadas("pix:" + id + ":recebimento")).isEqualTo(3); // 2 falhas + 1 sucesso
            assertThat(RAZAO.saldo(maria.id())).isEqualByComparingTo("50.00");
            assertThat(RAZAO.saldo(joao.id())).isEqualByComparingTo("250.00");
        }

        @Test
        @DisplayName("o caso traiçoeiro — o razão creditou, mas a resposta não chegou a tempo: NÃO estorna, repete e conclui")
        void tempoEsgotadoNaoEhRecusa() {
            Conta joao = novaConta("João", "300.00");
            Conta maria = novaConta("Maria", "0.00");
            String chave = chaveEmail(maria);
            RAZAO.lentoDepoisDeAplicar("recebimentos", 2_000); // o tempo-limite do Pix no teste é 800 ms

            String id = pix(chave(), joao.id(), chave, "80.00")
                    .statusCode(202)
                    .body("situacao", equalTo("DEBITADA"))
                    .extract().path("id");
            // Neste instante, o razão JÁ creditou a Maria. Se a Saga tratasse o tempo esgotado como
            // recusa e estornasse, o João teria o dinheiro de volta E a Maria ficaria com ele:
            // R$ 80 criados do nada.
            esperarSituacao(id, "CONCLUIDA");
            consultar(id).body("lancamentos.estorno", nullValue());
            assertThat(RAZAO.saldo(joao.id())).isEqualByComparingTo("220.00");
            assertThat(RAZAO.saldo(maria.id())).isEqualByComparingTo("80.00");
            assertThat(RAZAO.liquidacao()).isEqualByComparingTo("0");
            assertThat(RAZAO.chamadas("pix:" + id + ":estorno")).isZero();
        }

        @Test
        @DisplayName("o serviço morreu depois do débito, com a reserva na mão: outra execução assume e conclui")
        void servicoMorreuNoMeio() throws Exception {
            Conta joao = novaConta("João", "300.00");
            Conta maria = novaConta("Maria", "0.00");
            String chave = chaveEmail(maria);
            UUID id = UUID.randomUUID();
            UUID envio = RAZAO.jaEnviado("pix:" + id + ":envio", joao.id(), "70.00");
            Instant ha1Minuto = Instant.now().minusSeconds(60);
            try (Connection c = banco.getConnection();
                    PreparedStatement sql = c.prepareStatement("""
                            INSERT INTO transferencia_pix (id, chave_idempotencia, pagador_conta_id, pagador_nome,
                                recebedor_conta_id, recebedor_nome, recebedor_chave, recebedor_tipo_chave, valor,
                                situacao, lancamento_envio_id, tentativas, proxima_tentativa_em, reservada_ate, criada_em)
                            VALUES (?, ?, ?, 'João', ?, 'Maria', ?, 'EMAIL', 70.00, 'DEBITADA', ?, 0, ?, ?, ?)
                            """)) {
                sql.setObject(1, id);
                sql.setString(2, chave());
                sql.setObject(3, joao.id());
                sql.setObject(4, maria.id());
                sql.setString(5, chave);
                sql.setObject(6, envio);
                sql.setTimestamp(7, Timestamp.from(ha1Minuto));
                sql.setTimestamp(8, Timestamp.from(ha1Minuto.plusSeconds(30))); // reserva vencida
                sql.setTimestamp(9, Timestamp.from(ha1Minuto));
                sql.executeUpdate();
            }

            esperarSituacao(id.toString(), "CONCLUIDA");
            assertThat(RAZAO.saldo(maria.id())).isEqualByComparingTo("70.00");
            assertThat(RAZAO.liquidacao()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("o que é recusado antes de mover dinheiro")
    class Recusas {

        @Test
        @DisplayName("chave que ninguém tem: 404 chave-nao-encontrada")
        void chaveInexistente() {
            Conta joao = novaConta("João", "10.00");
            pix(chave(), joao.id(), "ninguem@exemplo.com", "1.00")
                    .statusCode(404)
                    .body("type", equalTo("urn:ledgerly:erro:chave-nao-encontrada"));
        }

        @Test
        @DisplayName("Pix para a própria conta: 422 transferencia-invalida")
        void mesmaConta() {
            Conta joao = novaConta("João", "10.00");
            pix(chave(), joao.id(), chaveEmail(joao), "1.00")
                    .statusCode(422)
                    .body("type", equalTo("urn:ledgerly:erro:transferencia-invalida"));
        }

        @Test
        @DisplayName("pagador bloqueado: 422 conta-inativa")
        void pagadorBloqueado() {
            Conta joao = novaConta("João", "10.00");
            Conta maria = novaConta("Maria", "0.00");
            String chave = chaveEmail(maria);
            ServicosFalsos.CONTAS.bloquear(joao.id());
            pix(chave(), joao.id(), chave, "1.00").statusCode(422).body("type", equalTo("urn:ledgerly:erro:conta-inativa"));
        }

        @Test
        @DisplayName("serviço de contas fora do ar: 503 com Retry-After, e nada foi criado")
        void contasForaDoAr() {
            Conta joao = novaConta("João", "10.00");
            Conta maria = novaConta("Maria", "0.00");
            String chave = chaveEmail(maria);
            ServicosFalsos.CONTAS.foraDoAr(true);
            pix(chave(), joao.id(), chave, "1.00")
                    .statusCode(503)
                    .header("Retry-After", "5")
                    .body("type", equalTo("urn:ledgerly:erro:servico-indisponivel"));
            assertThat(RAZAO.lancamentos()).isZero();
        }

        @Test
        @DisplayName("pedido sem Idempotency-Key, sem chave, sem valor ou com valor mal escrito: 400")
        void pedidosInvalidos() {
            Conta joao = novaConta("João", "10.00");
            io.restassured.RestAssured.given().contentType(io.restassured.http.ContentType.JSON)
                    .body("{\"pagadorContaId\":\"%s\",\"chave\":\"m@exemplo.com\",\"valor\":\"1.00\"}".formatted(joao.id()))
                    .post("/v1/transferencias").then()
                    .statusCode(400).body("type", equalTo("urn:ledgerly:erro:requisicao-invalida"));
            pix(chave(), joao.id(), null, "1.00").statusCode(422).body("type", equalTo("urn:ledgerly:erro:transferencia-invalida"));
            pix(chave(), joao.id(), "m@exemplo.com", null).statusCode(422);
            pix(chave(), joao.id(), "m@exemplo.com", "1,00")
                    .statusCode(400)
                    .body("campos.valor", org.hamcrest.Matchers.containsString("10.50"));
            io.restassured.RestAssured.given().contentType(io.restassured.http.ContentType.JSON)
                    .header("Idempotency-Key", chave()).body("{ isto não é json")
                    .post("/v1/transferencias").then()
                    .statusCode(400).body("type", equalTo("urn:ledgerly:erro:requisicao-invalida"));
        }

        @Test
        @DisplayName("transferência inexistente: 404")
        void inexistente() {
            consultar(UUID.randomUUID().toString())
                    .statusCode(404)
                    .body("type", equalTo("urn:ledgerly:erro:transferencia-nao-encontrada"));
        }
    }
}
