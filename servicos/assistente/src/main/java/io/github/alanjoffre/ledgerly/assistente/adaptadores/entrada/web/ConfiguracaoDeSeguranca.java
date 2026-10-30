package io.github.alanjoffre.ledgerly.assistente.adaptadores.entrada.web;

import static org.springframework.http.HttpMethod.POST;

import io.github.alanjoffre.ledgerly.seguranca.Papeis;
import io.github.alanjoffre.ledgerly.seguranca.SegurancaDeRecurso;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Só o cliente pergunta. A analista tem o backoffice; os serviços não têm o que perguntar. */
@Configuration(proxyBeanMethods = false)
class ConfiguracaoDeSeguranca {

    @Bean
    SecurityFilterChain seguranca(HttpSecurity http) throws Exception {
        return SegurancaDeRecurso.configurar(http, regras -> regras
                .requestMatchers(POST, "/v1/assistente/perguntas").hasRole(Papeis.CLIENTE)
                .requestMatchers("/v1/**").denyAll());
    }
}
