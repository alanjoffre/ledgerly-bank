package io.github.alanjoffre.ledgerly.razao.adaptadores.entrada.web;

import io.github.alanjoffre.ledgerly.seguranca.Papeis;
import io.github.alanjoffre.ledgerly.seguranca.SegurancaDeRecurso;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * O razão só fala com outros serviços: toda a API exige o papel {@code servico} (ADR-0027).
 *
 * <p>Nenhuma pessoa chama o razão, nem pelo BFF — o gateway não tem rota para ele. Quem mostra o
 * saldo ao cliente é o contas, que confere o dono da conta antes de perguntar ao razão. Assim um
 * erro de autorização no razão não existe: ele não tem o que autorizar por pessoa.
 */
@Configuration(proxyBeanMethods = false)
class ConfiguracaoDeSeguranca {

    @Bean
    SecurityFilterChain seguranca(HttpSecurity http) throws Exception {
        return SegurancaDeRecurso.configurar(http, regras -> regras.requestMatchers("/v1/**").hasRole(Papeis.SERVICO));
    }
}
