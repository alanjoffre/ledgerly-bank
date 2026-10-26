package io.github.alanjoffre.ledgerly.contas.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.alanjoffre.ledgerly.contas.dominio.CpfsDeTeste;
import io.github.alanjoffre.ledgerly.contas.dominio.SituacaoConta;
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

@DisplayName("API REST de contas")
@AutoConfigureMockMvc
class ApiIT extends TesteDeIntegracao {

    @Autowired
    private MockMvcTester http;

    private MvcTestResult cadastrar(String nome, String cpf) {
        return http.post().uri("/v1/clientes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"%s\",\"cpf\":\"%s\"}".formatted(nome, cpf)).exchange();
    }

    private UUID novoCliente() throws Exception {
        return id(cadastrar("Maria Exemplo", CpfsDeTeste.gerar()));
    }

    private MvcTestResult abrirContaDe(UUID clienteId) {
        return http.post().uri("/v1/contas").contentType(MediaType.APPLICATION_JSON)
                .content("{\"clienteId\":\"%s\"}".formatted(clienteId)).exchange();
    }

    private UUID novaConta() throws Exception {
        return id(abrirContaDe(novoCliente()));
    }

    private static UUID id(MvcTestResult resultado) throws Exception {
        return UUID.fromString(JsonPath.read(resultado.getResponse().getContentAsString(), "$.id"));
    }

    @Nested
    @DisplayName("clientes")
    class Clientes {

        @Test
        @DisplayName("cadastro: 201, CPF mascarado na resposta, nome normalizado")
        void cadastro() {
            String cpf = CpfsDeTeste.gerar();
            assertThat(cadastrar("  Maria   Exemplo ", cpf))
                    .hasStatus(HttpStatus.CREATED)
                    .bodyJson()
                    .hasPathSatisfying("$.nome", v -> v.assertThat().isEqualTo("Maria Exemplo"))
                    .hasPathSatisfying("$.cpf", v -> v.assertThat().isEqualTo(
                            "***." + cpf.substring(3, 6) + "." + cpf.substring(6, 9) + "-**"));
        }

        @Test
        @DisplayName("CPF repetido (com ou sem máscara): 409")
        void cpfRepetido() {
            String cpf = CpfsDeTeste.gerar();
            cadastrar("Maria Exemplo", cpf);
            String comMascara = cpf.substring(0, 3) + "." + cpf.substring(3, 6) + "." + cpf.substring(6, 9) + "-" + cpf.substring(9);
            assertThat(cadastrar("Outra Pessoa", comMascara))
                    .hasStatus(HttpStatus.CONFLICT)
                    .bodyJson().hasPathSatisfying("$.type", v -> v.assertThat().isEqualTo("urn:ledgerly:erro:cpf-ja-cadastrado"));
        }

        @Test
        @DisplayName("CPF com dígito errado: 400 cpf-invalido; nome vazio: 400 com o campo")
        void invalidos() {
            String cpf = CpfsDeTeste.gerar();
            String errado = cpf.substring(0, 10) + ((cpf.charAt(10) - '0' + 1) % 10);
            assertThat(cadastrar("Maria Exemplo", errado))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson().hasPathSatisfying("$.type", v -> v.assertThat().isEqualTo("urn:ledgerly:erro:cpf-invalido"));
            assertThat(cadastrar("", cpf))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson().hasPath("$.campos.nome");
        }

        @Test
        @DisplayName("consultar cliente e listar contas; cliente inexistente: 404")
        void consultar() throws Exception {
            UUID cliente = novoCliente();
            abrirContaDe(cliente);
            abrirContaDe(cliente);
            assertThat(http.get().uri("/v1/clientes/{id}", cliente)).hasStatusOk();
            assertThat(http.get().uri("/v1/clientes/{id}/contas", cliente))
                    .hasStatusOk().bodyJson().hasPathSatisfying("$.length()", v -> v.assertThat().isEqualTo(2));
            assertThat(http.get().uri("/v1/clientes/{id}", UUID.randomUUID())).hasStatus(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("contas")
    class Contas {

        @Test
        @DisplayName("abrir: 201, agência 0001, número com dígito, e a conta nasce também no razão")
        void abrir() throws Exception {
            MvcTestResult resultado = abrirContaDe(novoCliente());
            assertThat(resultado).hasStatus(HttpStatus.CREATED)
                    .bodyJson()
                    .hasPathSatisfying("$.agencia", v -> v.assertThat().isEqualTo("0001"))
                    .hasPathSatisfying("$.numero", v -> v.assertThat().asString().matches("\\d{6}-\\d"))
                    .hasPathSatisfying("$.situacao", v -> v.assertThat().isEqualTo("ATIVA"));
            assertThat(razao().conhece(id(resultado))).isTrue();
        }

        @Test
        @DisplayName("abrir para cliente inexistente: 404")
        void clienteInexistente() {
            assertThat(abrirContaDe(UUID.randomUUID())).hasStatus(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("consultar traz o saldo do razão")
        void consultarComSaldo() throws Exception {
            UUID conta = novaConta();
            razao().definirSaldo(conta, "42.10");
            assertThat(http.get().uri("/v1/contas/{id}", conta))
                    .hasStatusOk().bodyJson()
                    .hasPathSatisfying("$.saldo", v -> v.assertThat().isEqualTo("42.10"))
                    .hasPathSatisfying("$.moeda", v -> v.assertThat().isEqualTo("BRL"));
            assertThat(http.get().uri("/v1/contas/{id}", UUID.randomUUID())).hasStatus(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("bloquear e desbloquear refletem no razão; transição inválida: 422")
        void bloqueio() throws Exception {
            UUID conta = novaConta();
            assertThat(http.delete().uri("/v1/contas/{id}/bloqueio", conta))
                    .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                    .bodyJson().hasPathSatisfying("$.type", v -> v.assertThat().isEqualTo("urn:ledgerly:erro:transicao-invalida"));

            assertThat(http.post().uri("/v1/contas/{id}/bloqueio", conta)).hasStatusOk()
                    .bodyJson().hasPathSatisfying("$.situacao", v -> v.assertThat().isEqualTo("BLOQUEADA"));
            assertThat(razao().situacao(conta)).isEqualTo(SituacaoConta.BLOQUEADA);

            assertThat(http.delete().uri("/v1/contas/{id}/bloqueio", conta)).hasStatusOk();
            assertThat(razao().situacao(conta)).isEqualTo(SituacaoConta.ATIVA);
        }

        @Test
        @DisplayName("encerrar com saldo: 422 e a conta continua ATIVA aqui e no razão")
        void encerrarComSaldo() throws Exception {
            UUID conta = novaConta();
            razao().definirSaldo(conta, "5.00");
            assertThat(http.post().uri("/v1/contas/{id}/encerramento", conta))
                    .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                    .bodyJson().hasPathSatisfying("$.type", v -> v.assertThat().isEqualTo("urn:ledgerly:erro:encerramento-com-saldo"));
            assertThat(situacaoNoBanco(conta)).isEqualTo("ATIVA");
            assertThat(razao().situacao(conta)).isEqualTo(SituacaoConta.ATIVA);
        }

        @Test
        @DisplayName("encerrar com saldo zero: 200, com data de encerramento")
        void encerrar() throws Exception {
            UUID conta = novaConta();
            assertThat(http.post().uri("/v1/contas/{id}/encerramento", conta))
                    .hasStatusOk().bodyJson().hasPath("$.encerradaEm");
            assertThat(situacaoNoBanco(conta)).isEqualTo("ENCERRADA");
        }
    }

    @Nested
    @DisplayName("razão fora do ar")
    class RazaoForaDoAr {

        @Test
        @DisplayName("abrir conta: 503 + Retry-After, e NENHUMA conta fica gravada pela metade")
        void abrirSemRazao() throws Exception {
            UUID cliente = novoCliente();
            razao().derrubar();

            assertThat(abrirContaDe(cliente))
                    .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .headers().hasValue("Retry-After", "5");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM conta", Long.class)).isZero();
        }

        @Test
        @DisplayName("bloquear: 503, e a conta continua ATIVA (a transação local foi desfeita)")
        void bloquearSemRazao() throws Exception {
            UUID conta = novaConta();
            razao().derrubar();
            assertThat(http.post().uri("/v1/contas/{id}/bloqueio", conta))
                    .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .bodyJson().hasPathSatisfying("$.type", v -> v.assertThat().isEqualTo("urn:ledgerly:erro:razao-indisponivel"));
            assertThat(situacaoNoBanco(conta)).isEqualTo("ATIVA");
        }
    }

    private String situacaoNoBanco(UUID conta) {
        return jdbc.queryForObject("SELECT situacao FROM conta WHERE id = ?", String.class, conta);
    }
}
