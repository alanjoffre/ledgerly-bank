package io.github.alanjoffre.ledgerly.contas.adaptadores.entrada.web;

import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

import io.github.alanjoffre.ledgerly.seguranca.Papeis;
import io.github.alanjoffre.ledgerly.seguranca.SegurancaDeRecurso;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Quem pode chamar cada rota do contas (o papel). O dono de cada conta é conferido depois, no caso
 * de uso ({@link Permissoes}): a regra por papel não sabe de quem é a conta {@code {id}}.
 *
 * <table>
 *   <caption>Rotas e papéis</caption>
 *   <tr><th>Rota</th><th>Papel</th></tr>
 *   <tr><td>cadastrar cliente, abrir conta, encerrar, titular, titularidade</td><td>servico</td></tr>
 *   <tr><td>bloquear, desbloquear</td><td>analista, servico</td></tr>
 *   <tr><td>consultar cliente, contas do cliente, conta com saldo</td><td>cliente (dono), analista, servico</td></tr>
 * </table>
 */
@Configuration(proxyBeanMethods = false)
class ConfiguracaoDeSeguranca {

    @Bean
    SecurityFilterChain seguranca(HttpSecurity http) throws Exception {
        return SegurancaDeRecurso.configurar(http, regras -> regras
                .requestMatchers(POST, "/v1/clientes", "/v1/contas").hasRole(Papeis.SERVICO)
                .requestMatchers(POST, "/v1/contas/*/encerramento", "/v1/contas/*/titularidade").hasRole(Papeis.SERVICO)
                .requestMatchers(GET, "/v1/contas/*/titular").hasRole(Papeis.SERVICO)
                .requestMatchers(POST, "/v1/contas/*/bloqueio").hasAnyRole(Papeis.ANALISTA, Papeis.SERVICO)
                .requestMatchers(DELETE, "/v1/contas/*/bloqueio").hasAnyRole(Papeis.ANALISTA, Papeis.SERVICO)
                .requestMatchers(GET, "/v1/clientes/*", "/v1/clientes/*/contas", "/v1/contas/*")
                        .hasAnyRole(Papeis.CLIENTE, Papeis.ANALISTA, Papeis.SERVICO)
                .requestMatchers("/v1/**").denyAll());
    }
}
