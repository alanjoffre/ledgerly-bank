package io.github.alanjoffre.ledgerly.seguranca;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.alanjoffre.ledgerly.seguranca.teste.TokensDeTeste;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A configuração comum com um token de verdade passando pelo decodificador, pelos validadores e
 * pelo conversor — cada caso de recusa precisa dar 401 (quem é você?) ou 403 (você não pode).
 */
@SpringBootTest(classes = SegurancaDeRecursoTest.App.class)
@AutoConfigureMockMvc
@DisplayName("Segurança de recurso: 401, 403 e o Usuario no controller")
class SegurancaDeRecursoTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @Bean
        JwtDecoder decodificador() {
            return TokensDeTeste.decodificador();
        }

        @Bean
        SecurityFilterChain seguranca(HttpSecurity http) throws Exception {
            return SegurancaDeRecurso.configurar(http, r -> r.requestMatchers("/analista").hasRole(Papeis.ANALISTA));
        }

        @RestController
        static class Eco {
            @GetMapping("/eu")
            String eu(@AuthenticationPrincipal Usuario usuario) {
                return usuario.nome() + "|" + usuario.clienteId().map(UUID::toString).orElse("-");
            }

            @GetMapping("/analista")
            String analista() {
                return "ok";
            }
        }
    }

    @Autowired
    MockMvcTester mvc;

    private MockMvcTester.MockMvcRequestBuilder eu(String autorizacao) {
        return mvc.get().uri("/eu").header(HttpHeaders.AUTHORIZATION, autorizacao);
    }

    @Test
    @DisplayName("token bom: o controller recebe o Usuario")
    void tokenBom() {
        UUID cliente = UUID.randomUUID();
        assertThat(eu(TokensDeTeste.cliente(cliente))).hasStatusOk()
                .bodyText().isEqualTo("cliente-teste|" + cliente);
    }

    @Test
    @DisplayName("sem token: 401 em Problem Details, com WWW-Authenticate")
    void semToken() {
        assertThat(mvc.get().uri("/eu")).hasStatus(401)
                .hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"ledgerly\"")
                .bodyJson().extractingPath("$.type").isEqualTo("urn:ledgerly:erro:nao-autenticado");
    }

    @Test
    @DisplayName("vencido, outro emissor, outra audiência, assinatura forjada, lixo: 401")
    void tokensRuins() {
        for (String ruim : new String[] {
                TokensDeTeste.token().papeis(Papeis.CLIENTE).vencido().bearer(),
                TokensDeTeste.token().papeis(Papeis.CLIENTE).emissor("https://outro/realms/x").bearer(),
                TokensDeTeste.token().papeis(Papeis.CLIENTE).audiencia("outro-sistema").bearer(),
                TokensDeTeste.token().papeis(Papeis.CLIENTE).assinadoPorOutraChave().bearer(),
                "Bearer nao.e.jwt"}) {
            assertThat(eu(ruim)).as(ruim).hasStatus(401);
        }
    }

    @Test
    @DisplayName("papel errado: 403 acesso-negado; papel certo: 200")
    void papel() {
        assertThat(mvc.get().uri("/analista").header(HttpHeaders.AUTHORIZATION, TokensDeTeste.servico()))
                .hasStatus(403).bodyJson().extractingPath("$.type").isEqualTo("urn:ledgerly:erro:acesso-negado");
        assertThat(mvc.get().uri("/analista").header(HttpHeaders.AUTHORIZATION, TokensDeTeste.analista()))
                .hasStatusOk();
    }

    @Test
    @DisplayName("health aberto, sem token (aqui não há actuator: 404, e não 401)")
    void saude() {
        assertThat(mvc.get().uri("/actuator/health")).hasStatus(404);
    }
}
