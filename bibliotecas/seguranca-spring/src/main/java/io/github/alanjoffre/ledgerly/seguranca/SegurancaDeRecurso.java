package io.github.alanjoffre.ledgerly.seguranca;

import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * A configuração que todo serviço Spring repete; cada um só escreve as próprias regras por papel.
 *
 * <ul>
 *   <li><b>Sem sessão</b> (STATELESS): cada pedido traz o token; o serviço não lembra de ninguém.</li>
 *   <li><b>CSRF desligado</b>: o CSRF explora o cookie que o navegador manda sozinho. Aqui não há
 *       cookie — o token vai no cabeçalho, que o navegador não põe sozinho. O CSRF é tratado no BFF,
 *       que tem sessão com cookie (ADR-0025).</li>
 *   <li>{@code /actuator/health} aberto: quem pergunta é o Kubernetes, sem token.</li>
 *   <li>O que as regras não citarem exige token válido; o que não existir dá 404 só para quem
 *       tem token (sem token, 401: a API não conta o que existe para quem não se identificou).</li>
 * </ul>
 */
public final class SegurancaDeRecurso {

    private SegurancaDeRecurso() {}

    public static SecurityFilterChain configurar(
            HttpSecurity http,
            Customizer<AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry> regras)
            throws Exception {
        var respostas = new RespostasDeSeguranca();
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(c -> c.disable())
                .authorizeHttpRequests(a -> {
                    a.requestMatchers("/actuator/health/**", "/actuator/info", "/error").permitAll();
                    regras.customize(a);
                    a.anyRequest().authenticated();
                })
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(new ConversorDeToken()))
                        .authenticationEntryPoint(respostas)
                        .accessDeniedHandler(respostas))
                .exceptionHandling(e -> e.authenticationEntryPoint(respostas).accessDeniedHandler(respostas));
        return http.build();
    }
}
