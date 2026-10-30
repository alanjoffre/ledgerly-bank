package io.github.alanjoffre.ledgerly.contas.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.alanjoffre.ledgerly.contas.dominio.CpfsDeTeste;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@DisplayName("API REST de contas")
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
        @DisplayName("abrir: 201, agência 0001, número com dígito, e o evento ContaAberta fica no Outbox")
        void abrir() throws Exception {
            MvcTestResult resultado = abrirContaDe(novoCliente());
            assertThat(resultado).hasStatus(HttpStatus.CREATED)
                    .bodyJson()
                    .hasPathSatisfying("$.agencia", v -> v.assertThat().isEqualTo("0001"))
                    .hasPathSatisfying("$.numero", v -> v.assertThat().asString().matches("\\d{6}-\\d"))
                    .hasPathSatisfying("$.situacao", v -> v.assertThat().isEqualTo("ATIVA"));
            assertThat(eventosNoOutbox(id(resultado))).containsExactly("ContaAberta");
        }

        @Test
        @DisplayName("abrir para cliente inexistente: 404 e nenhum evento")
        void clienteInexistente() {
            assertThat(abrirContaDe(UUID.randomUUID())).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Long.class)).isZero();
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
        @DisplayName("consultar conta que o razão ainda não conhece (evento a caminho): saldo 0.00")
        void consultarAntesDoRazao() throws Exception {
            UUID conta = novaConta(); // o falso só conhece a conta depois de definirSaldo
            assertThat(http.get().uri("/v1/contas/{id}", conta))
                    .hasStatusOk().bodyJson()
                    .hasPathSatisfying("$.saldo", v -> v.assertThat().isEqualTo("0.00"))
                    .hasPathSatisfying("$.moeda", v -> v.assertThat().isEqualTo("BRL"));
        }

        @Test
        @DisplayName("bloquear e desbloquear gravam os eventos em ordem; transição inválida: 422 e nenhum evento")
        void bloqueio() throws Exception {
            UUID conta = novaConta();
            assertThat(http.delete().uri("/v1/contas/{id}/bloqueio", conta))
                    .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                    .bodyJson().hasPathSatisfying("$.type", v -> v.assertThat().isEqualTo("urn:ledgerly:erro:transicao-invalida"));
            assertThat(eventosNoOutbox(conta)).containsExactly("ContaAberta");

            assertThat(http.post().uri("/v1/contas/{id}/bloqueio", conta)).hasStatusOk()
                    .bodyJson().hasPathSatisfying("$.situacao", v -> v.assertThat().isEqualTo("BLOQUEADA"));
            assertThat(http.delete().uri("/v1/contas/{id}/bloqueio", conta)).hasStatusOk();
            assertThat(eventosNoOutbox(conta)).containsExactly("ContaAberta", "ContaBloqueada", "ContaDesbloqueada");
        }

        @Test
        @DisplayName("encerrar com saldo: 422, a conta continua ATIVA e o ContaEncerrada NÃO fica gravado")
        void encerrarComSaldo() throws Exception {
            UUID conta = novaConta();
            razao().definirSaldo(conta, "5.00");
            assertThat(http.post().uri("/v1/contas/{id}/encerramento", conta))
                    .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                    .bodyJson().hasPathSatisfying("$.type", v -> v.assertThat().isEqualTo("urn:ledgerly:erro:encerramento-com-saldo"));
            assertThat(situacaoNoBanco(conta)).isEqualTo("ATIVA");
            assertThat(razao().encerrada(conta)).isFalse();
            assertThat(eventosNoOutbox(conta)).containsExactly("ContaAberta");
        }

        @Test
        @DisplayName("encerrar com saldo zero: 200, com data de encerramento, no razão e com o evento")
        void encerrar() throws Exception {
            UUID conta = novaConta();
            razao().definirSaldo(conta, "0.00");
            assertThat(http.post().uri("/v1/contas/{id}/encerramento", conta))
                    .hasStatusOk().bodyJson().hasPath("$.encerradaEm");
            assertThat(situacaoNoBanco(conta)).isEqualTo("ENCERRADA");
            assertThat(razao().encerrada(conta)).isTrue();
            assertThat(eventosNoOutbox(conta)).containsExactly("ContaAberta", "ContaEncerrada");
        }
    }

    @Nested
    @DisplayName("titularidade")
    class Titularidade {

        @Test
        @DisplayName("CPF do titular (com ou sem máscara): confere; outro CPF válido: não confere")
        void confere() throws Exception {
            String cpf = CpfsDeTeste.gerar();
            UUID conta = id(abrirContaDe(id(cadastrar("Maria Exemplo", cpf))));
            String comMascara = cpf.substring(0, 3) + "." + cpf.substring(3, 6) + "." + cpf.substring(6, 9) + "-" + cpf.substring(9);

            assertThat(titularidade(conta, cpf)).hasStatusOk()
                    .bodyJson().hasPathSatisfying("$.confere", v -> v.assertThat().isEqualTo(true));
            assertThat(titularidade(conta, comMascara)).hasStatusOk()
                    .bodyJson().hasPathSatisfying("$.confere", v -> v.assertThat().isEqualTo(true));
            assertThat(titularidade(conta, CpfsDeTeste.gerar())).hasStatusOk()
                    .bodyJson().hasPathSatisfying("$.confere", v -> v.assertThat().isEqualTo(false));
        }

        @Test
        @DisplayName("a resposta não traz o CPF; conta inexistente: 404; CPF mal formado: 400 cpf-invalido")
        void erros() throws Exception {
            String cpf = CpfsDeTeste.gerar();
            UUID conta = id(abrirContaDe(id(cadastrar("Maria Exemplo", cpf))));

            assertThat(titularidade(conta, cpf).getResponse().getContentAsString()).doesNotContain(cpf.substring(3, 9));
            assertThat(titularidade(UUID.randomUUID(), cpf)).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(titularidade(conta, "123"))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson().hasPathSatisfying("$.type", v -> v.assertThat().isEqualTo("urn:ledgerly:erro:cpf-invalido"));
            assertThat(titularidade(conta, ""))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson().hasPath("$.campos.cpf");
        }

        @Test
        @DisplayName("titular: nome, cliente, situação e abertura, sem saldo e sem CPF; conta inexistente: 404")
        void titular() throws Exception {
            String cpf = CpfsDeTeste.gerar();
            UUID cliente = id(cadastrar("Maria Exemplo", cpf));
            UUID conta = id(abrirContaDe(cliente));
            http.post().uri("/v1/contas/{id}/bloqueio", conta).exchange();

            MvcTestResult resposta = http.get().uri("/v1/contas/{id}/titular", conta).exchange();
            assertThat(resposta).hasStatusOk().bodyJson()
                    .hasPathSatisfying("$.nome", v -> v.assertThat().isEqualTo("Maria Exemplo"))
                    .hasPathSatisfying("$.clienteId", v -> v.assertThat().isEqualTo(cliente.toString()))
                    .hasPathSatisfying("$.situacao", v -> v.assertThat().isEqualTo("BLOQUEADA"))
                    .hasPathSatisfying("$.abertaEm", v -> v.assertThat().asString().isNotBlank())
                    .doesNotHavePath("$.saldo");
            assertThat(resposta.getResponse().getContentAsString()).doesNotContain(cpf.substring(3, 9));
            assertThat(http.get().uri("/v1/contas/{id}/titular", UUID.randomUUID())).hasStatus(HttpStatus.NOT_FOUND);
        }

        private MvcTestResult titularidade(UUID conta, String cpf) {
            return http.post().uri("/v1/contas/{id}/titularidade", conta).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"cpf\":\"%s\"}".formatted(cpf)).exchange();
        }
    }

    @Nested
    @DisplayName("razão fora do ar")
    class RazaoForaDoAr {

        @Test
        @DisplayName("abrir conta FUNCIONA: a conta e o evento ficam gravados; o razão recebe quando voltar")
        void abrirSemRazao() throws Exception {
            UUID cliente = novoCliente();
            razao().derrubar();

            MvcTestResult resultado = abrirContaDe(cliente);
            assertThat(resultado).hasStatus(HttpStatus.CREATED);
            assertThat(eventosNoOutbox(id(resultado))).containsExactly("ContaAberta");
        }

        @Test
        @DisplayName("bloquear FUNCIONA: só avisa o razão, por evento")
        void bloquearSemRazao() throws Exception {
            UUID conta = novaConta();
            razao().derrubar();
            assertThat(http.post().uri("/v1/contas/{id}/bloqueio", conta)).hasStatusOk();
            assertThat(situacaoNoBanco(conta)).isEqualTo("BLOQUEADA");
        }

        @Test
        @DisplayName("titular: responde normalmente — nome e situação não dependem do razão")
        void titularSemRazao() throws Exception {
            UUID conta = novaConta();
            razao().derrubar();
            assertThat(http.get().uri("/v1/contas/{id}/titular", conta)).hasStatusOk()
                    .bodyJson().hasPathSatisfying("$.situacao", v -> v.assertThat().isEqualTo("ATIVA"));
        }

        @Test
        @DisplayName("consultar: 503 + Retry-After (o saldo é do razão)")
        void consultarSemRazao() throws Exception {
            UUID conta = novaConta();
            razao().derrubar();
            assertThat(http.get().uri("/v1/contas/{id}", conta))
                    .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .headers().hasValue("Retry-After", "5");
        }

        @Test
        @DisplayName("encerrar: 503, a conta continua ATIVA e o ContaEncerrada não fica gravado")
        void encerrarSemRazao() throws Exception {
            UUID conta = novaConta();
            razao().derrubar();
            assertThat(http.post().uri("/v1/contas/{id}/encerramento", conta))
                    .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .bodyJson().hasPathSatisfying("$.type", v -> v.assertThat().isEqualTo("urn:ledgerly:erro:razao-indisponivel"));
            assertThat(situacaoNoBanco(conta)).isEqualTo("ATIVA");
            assertThat(eventosNoOutbox(conta)).containsExactly("ContaAberta");
        }
    }

    private String situacaoNoBanco(UUID conta) {
        return jdbc.queryForObject("SELECT situacao FROM conta WHERE id = ?", String.class, conta);
    }
}
