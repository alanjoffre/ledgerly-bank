package io.github.alanjoffre.ledgerly.contas.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.alanjoffre.ledgerly.contas.dominio.CpfsDeTeste;
import io.github.alanjoffre.ledgerly.seguranca.Papeis;
import io.github.alanjoffre.ledgerly.seguranca.teste.MockMvcComToken;
import io.github.alanjoffre.ledgerly.seguranca.teste.TokensDeTeste;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.context.WebApplicationContext;

/**
 * Quem pode o quê no contas: o papel (401/403) e o dono (404). O cliente vê só o que é dele; a
 * conta de outro cliente responde igual a uma conta que não existe (ADR-0026).
 */
@DisplayName("Autorização no contas")
class AutorizacaoIT extends TesteDeIntegracao {

    @Autowired
    private MockMvcTester comoServico;

    @Autowired
    private WebApplicationContext contexto;

    private MockMvcTester http;
    private UUID ana;
    private UUID contaDaAna;
    private UUID bruno;
    private UUID contaDoBruno;

    @BeforeEach
    void doisClientes() throws Exception {
        http = MockMvcComToken.semPadrao(contexto);
        ana = cliente("Ana Exemplo");
        contaDaAna = conta(ana);
        bruno = cliente("Bruno Exemplo");
        contaDoBruno = conta(bruno);
        razao().definirSaldo(contaDaAna, "100.00");
        razao().definirSaldo(contaDoBruno, "999.00");
    }

    private UUID cliente(String nome) throws Exception {
        return id(comoServico.post().uri("/v1/clientes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"%s\",\"cpf\":\"%s\"}".formatted(nome, CpfsDeTeste.gerar())).exchange());
    }

    private UUID conta(UUID cliente) throws Exception {
        return id(comoServico.post().uri("/v1/contas").contentType(MediaType.APPLICATION_JSON)
                .content("{\"clienteId\":\"%s\"}".formatted(cliente)).exchange());
    }

    private static UUID id(MvcTestResult resultado) throws Exception {
        return UUID.fromString(JsonPath.read(resultado.getResponse().getContentAsString(), "$.id"));
    }

    private MvcTestResult get(String token, String uri, Object... variaveis) {
        return http.get().uri(uri, variaveis).header(HttpHeaders.AUTHORIZATION, token).exchange();
    }

    @Nested
    @DisplayName("o dono (BOLA)")
    class Dono {

        @Test
        @DisplayName("a Ana vê o próprio cadastro, as próprias contas e o próprio saldo")
        void veOQueEhSeu() {
            String token = TokensDeTeste.cliente(ana);
            assertThat(get(token, "/v1/clientes/{id}", ana)).hasStatusOk();
            assertThat(get(token, "/v1/clientes/{id}/contas", ana)).hasStatusOk()
                    .bodyJson().extractingPath("$[0].id").isEqualTo(contaDaAna.toString());
            assertThat(get(token, "/v1/contas/{id}", contaDaAna)).hasStatusOk()
                    .bodyJson().extractingPath("$.saldo").isEqualTo("100.00");
        }

        @Test
        @DisplayName("a conta do Bruno, para a Ana: 404 igual ao de conta inexistente, e o razão nem é consultado")
        void contaDeOutroEhComoSeNaoExistisse() {
            String token = TokensDeTeste.cliente(ana);
            int antes = razao().consultasDeSaldo();

            var deOutro = get(token, "/v1/contas/{id}", contaDoBruno);
            var inexistente = get(token, "/v1/contas/{id}", UUID.randomUUID());

            assertThat(deOutro).hasStatus(404).bodyJson().extractingPath("$.type")
                    .isEqualTo("urn:ledgerly:erro:conta-nao-encontrada");
            assertThat(inexistente).hasStatus(404).bodyJson().extractingPath("$.type")
                    .isEqualTo("urn:ledgerly:erro:conta-nao-encontrada");
            // "999.00", não "999": o id da conta na resposta é aleatório e pode ter "999" (já aconteceu no CI).
            assertThat(deOutro.getResponse().getContentAsByteArray()).asString().doesNotContain("999.00");
            assertThat(razao().consultasDeSaldo()).as("o saldo do Bruno não foi buscado").isEqualTo(antes);
        }

        @Test
        @DisplayName("o cadastro e a lista de contas do Bruno, para a Ana: 404")
        void clienteDeOutro() {
            String token = TokensDeTeste.cliente(ana);
            assertThat(get(token, "/v1/clientes/{id}", bruno)).hasStatus(404)
                    .bodyJson().extractingPath("$.type").isEqualTo("urn:ledgerly:erro:cliente-nao-encontrado");
            assertThat(get(token, "/v1/clientes/{id}/contas", bruno)).hasStatus(404);
        }

        @Test
        @DisplayName("cliente sem cliente_id no token (ainda não vinculado): não é dono de nada")
        void semVinculo() {
            String token = TokensDeTeste.token().papeis(Papeis.CLIENTE).bearer();
            assertThat(get(token, "/v1/contas/{id}", contaDaAna)).hasStatus(404);
            assertThat(get(token, "/v1/clientes/{id}", ana)).hasStatus(404);
        }

        @Test
        @DisplayName("cliente_id da Ana com outro papel (analista não, nem papel nenhum): não vira dono")
        void claimSemPapel() {
            String token = TokensDeTeste.token().clienteId(ana).bearer();
            assertThat(get(token, "/v1/contas/{id}", contaDaAna)).hasStatus(403);
        }

        @Test
        @DisplayName("analista vê a conta de qualquer cliente")
        void analista() {
            assertThat(get(TokensDeTeste.analista(), "/v1/contas/{id}", contaDoBruno)).hasStatusOk();
            assertThat(get(TokensDeTeste.analista(), "/v1/clientes/{id}/contas", bruno)).hasStatusOk();
        }
    }

    @Nested
    @DisplayName("o papel")
    class Papel {

        @Test
        @DisplayName("sem token: 401")
        void semToken() {
            assertThat(http.get().uri("/v1/contas/{id}", contaDaAna)).hasStatus(401);
        }

        @Test
        @DisplayName("cliente não abre conta, não cadastra cliente, não lê titular nem titularidade: 403")
        void clienteNaoFazOQueEhDeServico() {
            String token = TokensDeTeste.cliente(ana);
            assertThat(http.post().uri("/v1/contas").header(HttpHeaders.AUTHORIZATION, token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"clienteId\":\"%s\"}".formatted(ana)))
                    .hasStatus(403);
            assertThat(http.post().uri("/v1/clientes").header(HttpHeaders.AUTHORIZATION, token)
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).hasStatus(403);
            assertThat(get(token, "/v1/contas/{id}/titular", contaDaAna)).hasStatus(403);
            assertThat(http.post().uri("/v1/contas/{id}/titularidade", contaDaAna).header(HttpHeaders.AUTHORIZATION, token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"cpf\":\"x\"}")).hasStatus(403);
            assertThat(http.post().uri("/v1/contas/{id}/encerramento", contaDaAna).header(HttpHeaders.AUTHORIZATION, token))
                    .hasStatus(403);
        }

        @Test
        @DisplayName("bloqueio: o cliente não pode (nem na própria conta); o analista pode")
        void bloqueio() {
            assertThat(http.post().uri("/v1/contas/{id}/bloqueio", contaDaAna)
                    .header(HttpHeaders.AUTHORIZATION, TokensDeTeste.cliente(ana))).hasStatus(403);
            assertThat(http.post().uri("/v1/contas/{id}/bloqueio", contaDaAna)
                    .header(HttpHeaders.AUTHORIZATION, TokensDeTeste.analista())).hasStatusOk()
                    .bodyJson().extractingPath("$.situacao").isEqualTo("BLOQUEADA");
            assertThat(http.delete().uri("/v1/contas/{id}/bloqueio", contaDaAna)
                    .header(HttpHeaders.AUTHORIZATION, TokensDeTeste.analista())).hasStatusOk();
        }

        @Test
        @DisplayName("analista não encerra conta nem lê o titular (rotas de serviço): 403")
        void analistaNaoFazOQueEhDeServico() {
            assertThat(http.post().uri("/v1/contas/{id}/encerramento", contaDaAna)
                    .header(HttpHeaders.AUTHORIZATION, TokensDeTeste.analista())).hasStatus(403);
            assertThat(get(TokensDeTeste.analista(), "/v1/contas/{id}/titular", contaDaAna)).hasStatus(403);
        }

        @Test
        @DisplayName("rota que não existe: negada (403), não aberta por engano")
        void negadoPorPadrao() {
            assertThat(http.put().uri("/v1/contas/{id}", contaDaAna)
                    .header(HttpHeaders.AUTHORIZATION, TokensDeTeste.servico())).hasStatus(403);
        }
    }
}
