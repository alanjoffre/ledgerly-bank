package io.github.alanjoffre.ledgerly.extrato.adaptadores.entrada.web;

import static org.springframework.http.HttpMethod.GET;

import io.github.alanjoffre.ledgerly.seguranca.Papeis;
import io.github.alanjoffre.ledgerly.seguranca.SegurancaDeRecurso;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Extrato e saldo: cliente (só o dono, conferido na consulta), analista e serviço. */
@Configuration(proxyBeanMethods = false)
class ConfiguracaoDeSeguranca {

    @Bean
    SecurityFilterChain seguranca(HttpSecurity http) throws Exception {
        return SegurancaDeRecurso.configurar(http, regras -> regras
                .requestMatchers(GET, "/v1/contas/*/extrato", "/v1/contas/*/saldo")
                        .hasAnyRole(Papeis.CLIENTE, Papeis.ANALISTA, Papeis.SERVICO)
                .requestMatchers("/v1/**").denyAll());
    }
}
