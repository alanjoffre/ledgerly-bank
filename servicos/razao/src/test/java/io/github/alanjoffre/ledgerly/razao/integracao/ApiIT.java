package io.github.alanjoffre.ledgerly.razao.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** A API REST de ponta a ponta: HTTP → controller → caso de uso → PostgreSQL. */
@DisplayName("API REST do razão")
@AutoConfigureMockMvc
class ApiIT extends TesteDeIntegracao {

    @Autowired
    private MockMvcTester http;

    private MvcTestResult abrirConta(UUID id) {
        return http.put().uri("/v1/contas/{id}", id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"moeda\":\"BRL\"}").exchange();
    }

    private MvcTestResult depositar(String chave, UUID conta, String valor) {
        return http.post().uri("/v1/depositos")
                .header("Idempotency-Key", chave)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contaId\":\"%s\",\"valor\":\"%s\",\"descricao\":\"teste\"}".formatted(conta, valor))
                .exchange();
    }

    @Nested
    @DisplayName("contas")
    class Contas {

        @Test
        @DisplayName("PUT /v1/contas/{id}: 201 na primeira vez, 200 nas seguintes")
        void abrir() {
            UUID id = UUID.randomUUID();
            assertThat(abrirConta(id)).hasStatus(HttpStatus.CREATED)
                    .bodyJson().hasPathSatisfying("$.saldo", v -> v.assertThat().isEqualTo("0.00"))
                    .hasPathSatisfying("$.natureza", v -> v.assertThat().isEqualTo("CREDORA"));
            assertThat(abrirConta(id)).hasStatus(HttpStatus.OK);
        }

        @Test
        @DisplayName("moeda inválida: 400 com o campo apontado")
        void moedaInvalida() {
            assertThat(http.put().uri("/v1/contas/{id}", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"moeda\":\"real\"}"))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson().hasPathSatisfying("$.campos.moeda", v -> v.assertThat().asString().contains("ISO 4217"));
        }

        @Test
        @DisplayName("GET de conta inexistente: 404 com type estável")
        void inexistente() {
            assertThat(http.get().uri("/v1/contas/{id}", UUID.randomUUID()))
                    .hasStatus(HttpStatus.NOT_FOUND)
                    .bodyJson().hasPathSatisfying("$.type", v -> v.assertThat().isEqualTo("urn:ledgerly:erro:conta-nao-encontrada"));
        }

        @Test
        @DisplayName("id que não é UUID: 400")
        void idInvalido() {
            assertThat(http.get().uri("/v1/contas/abc")).hasStatus(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("bloquear, conferir e extrato")
        void situacaoEExtrato() {
            UUID id = UUID.randomUUID();
            abrirConta(id);
            depositar(UUID.randomUUID().toString(), id, "80.00");

            assertThat(http.put().uri("/v1/contas/{id}/situacao", id)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"situacao\":\"BLOQUEADA\"}"))
                    .hasStatusOk()
                    .bodyJson().hasPathSatisfying("$.situacao", v -> v.assertThat().isEqualTo("BLOQUEADA"));

            assertThat(http.get().uri("/v1/contas/{id}/extrato?limite=10", id))
                    .hasStatusOk()
                    .bodyJson()
                    .hasPathSatisfying("$.conta.saldo", v -> v.assertThat().isEqualTo("80.00"))
                    .hasPathSatisfying("$.itens[0].tipo", v -> v.assertThat().isEqualTo("CREDITO"))
                    .hasPathSatisfying("$.itens[0].saldoApos", v -> v.assertThat().isEqualTo("80.00"));
        }
    }

    @Nested
    @DisplayName("lançamentos")
    class Lancamentos {

        @Test
        @DisplayName("depósito: 201 + Location; repetido: 200 + Idempotent-Replayed, mesmo id")
        void depositoIdempotente() throws Exception {
            UUID conta = UUID.randomUUID();
            abrirConta(conta);
            String chave = UUID.randomUUID().toString();

            MvcTestResult primeira = depositar(chave, conta, "10.50");
            assertThat(primeira).hasStatus(HttpStatus.CREATED).headers().containsHeader("Location");
            assertThat(primeira).bodyJson()
                    .hasPathSatisfying("$.valor", v -> v.assertThat().isEqualTo("10.50"))
                    .hasPathSatisfying("$.tipo", v -> v.assertThat().isEqualTo("DEPOSITO"));

            MvcTestResult segunda = depositar(chave, conta, "10.50");
            assertThat(segunda).hasStatusOk().headers().hasValue("Idempotent-Replayed", "true");
            assertThat(segunda.getResponse().getContentAsString())
                    .isEqualTo(primeira.getResponse().getContentAsString());

            String location = primeira.getResponse().getHeader("Location");
            assertThat(http.get().uri(location)).hasStatusOk();
        }

        @Test
        @DisplayName("mesma chave, valor diferente: 422 chave-idempotencia-reutilizada")
        void chaveReutilizada() {
            UUID conta = UUID.randomUUID();
            abrirConta(conta);
            String chave = UUID.randomUUID().toString();
            depositar(chave, conta, "10.00");
            assertThat(depositar(chave, conta, "99.00"))
                    .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                    .bodyJson().hasPathSatisfying("$.type",
                            v -> v.assertThat().isEqualTo("urn:ledgerly:erro:chave-idempotencia-reutilizada"));
        }

        @Test
        @DisplayName("sem o cabeçalho Idempotency-Key: 400 em português")
        void semChave() {
            assertThat(http.post().uri("/v1/depositos").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"contaId\":\"%s\",\"valor\":\"1\"}".formatted(UUID.randomUUID())))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson().hasPathSatisfying("$.detail",
                            v -> v.assertThat().isEqualTo("O cabeçalho 'Idempotency-Key' é obrigatório."));
        }

        @Test
        @DisplayName("valor como número com 3 casas ou negativo: 400 com o campo apontado")
        void valorInvalido() {
            UUID conta = UUID.randomUUID();
            assertThat(depositar(UUID.randomUUID().toString(), conta, "10.123"))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson().hasPathSatisfying("$.campos.valor", v -> v.assertThat().asString().contains("10.50"));
            assertThat(depositar(UUID.randomUUID().toString(), conta, "-5")).hasStatus(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("valor zero: 422 lancamento-invalido")
        void valorZero() {
            UUID conta = UUID.randomUUID();
            abrirConta(conta);
            assertThat(depositar(UUID.randomUUID().toString(), conta, "0.00"))
                    .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                    .bodyJson().hasPathSatisfying("$.type", v -> v.assertThat().isEqualTo("urn:ledgerly:erro:lancamento-invalido"));
        }

        @Test
        @DisplayName("JSON malformado: 400")
        void jsonMalformado() {
            assertThat(http.post().uri("/v1/depositos").header("Idempotency-Key", "k")
                            .contentType(MediaType.APPLICATION_JSON).content("{nao é json"))
                    .hasStatus(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("saque sem saldo: 422 saldo-insuficiente; transferência e integridade OK")
        void saqueSemSaldoETransferencia() {
            UUID origem = UUID.randomUUID();
            UUID destino = UUID.randomUUID();
            abrirConta(origem);
            abrirConta(destino);
            depositar(UUID.randomUUID().toString(), origem, "20.00");

            assertThat(http.post().uri("/v1/saques").header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"contaId\":\"%s\",\"valor\":\"20.01\"}".formatted(origem)))
                    .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                    .bodyJson().hasPathSatisfying("$.type", v -> v.assertThat().isEqualTo("urn:ledgerly:erro:saldo-insuficiente"));

            assertThat(http.post().uri("/v1/transferencias").header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"origemId\":\"%s\",\"destinoId\":\"%s\",\"valor\":\"7.25\"}".formatted(origem, destino)))
                    .hasStatus(HttpStatus.CREATED)
                    .bodyJson().hasPathSatisfying("$.partidas[0].saldoApos", v -> v.assertThat().isEqualTo("12.75"));

            assertThat(http.post().uri("/v1/saques").header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"contaId\":\"%s\",\"valor\":\"2.75\"}".formatted(origem)))
                    .hasStatus(HttpStatus.CREATED);

            assertThat(http.get().uri("/v1/integridade"))
                    .hasStatusOk()
                    .bodyJson().hasPathSatisfying("$.integro", v -> v.assertThat().isEqualTo(true));
        }

        @Test
        @DisplayName("limite do extrato fora da faixa: 400")
        void limiteInvalido() {
            UUID conta = UUID.randomUUID();
            abrirConta(conta);
            assertThat(http.get().uri("/v1/contas/{id}/extrato?limite=1000", conta)).hasStatus(HttpStatus.BAD_REQUEST);
        }
    }
}
