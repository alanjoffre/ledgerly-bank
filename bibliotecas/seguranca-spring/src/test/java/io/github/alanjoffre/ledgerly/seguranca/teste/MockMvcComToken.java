package io.github.alanjoffre.ledgerly.seguranca.teste;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import jakarta.servlet.Filter;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Os testes de integração antigos (Fases 1 a 3) não mandavam token. Em vez de mexer em cada um, o
 * {@code MockMvc} injetado passa a mandar um token padrão; o teste que quer outro manda o próprio
 * cabeçalho, que vence o padrão.
 */
public final class MockMvcComToken {

    private MockMvcComToken() {}

    /** Todo pedido do {@code MockMvc} injetado leva este {@code Authorization}, se o teste não mandar outro. */
    public static MockMvcBuilderCustomizer padrao(String autorizacao) {
        return construtor -> construtor.defaultRequest(get("/").header(HttpHeaders.AUTHORIZATION, autorizacao));
    }

    /** Um {@code MockMvc} com a mesma cadeia de segurança e sem cabeçalho padrão: para os testes de 401. */
    public static MockMvcTester semPadrao(WebApplicationContext contexto) {
        Filter seguranca = contexto.getBean("springSecurityFilterChain", Filter.class);
        return MockMvcTester.create(MockMvcBuilders.webAppContextSetup(contexto).addFilters(seguranca).build());
    }
}
