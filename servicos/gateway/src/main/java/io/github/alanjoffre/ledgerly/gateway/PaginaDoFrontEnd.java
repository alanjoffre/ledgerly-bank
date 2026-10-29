package io.github.alanjoffre.ledgerly.gateway;

import static org.springframework.web.servlet.function.RequestPredicates.GET;
import static org.springframework.web.servlet.function.RequestPredicates.accept;
import static org.springframework.web.servlet.function.RequestPredicates.path;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * A tela do front-end, servida pelo próprio gateway: mesma origem que a API, então o cookie de
 * sessão e o de CSRF funcionam sem CORS.
 *
 * <p>Os arquivos ({@code /assets/...}) saem da pasta construída pelo Vite
 * ({@code spring.web.resources.static-locations}). Uma navegação para um endereço da tela
 * ({@code /contas/123}) não tem arquivo: devolve o {@code index.html}, e o roteador do React mostra
 * a página certa — o "fallback" de SPA (<i>single-page application</i>).
 */
@Configuration
class PaginaDoFrontEnd {

    @Bean
    RouterFunction<ServerResponse> paginaDaTela(PropriedadesDoBff bff, ResourceLoader carregador) {
        Resource indice = carregador.getResource(bff.pagina() + "index.html");
        return RouterFunctions.route(
                GET("/**")
                        .and(accept(MediaType.TEXT_HTML))
                        .and(path("/api/**").negate())
                        .and(path("/actuator/**").negate())
                        // O Spring encaminha os erros para /error: servir a tela ali transformaria um 403 num 200.
                        .and(path("/error").negate())
                        .and(PaginaDoFrontEnd::semExtensao),
                pedido -> indice.exists()
                        ? ServerResponse.ok()
                                .contentType(MediaType.TEXT_HTML)
                                // O index.html aponta para os arquivos com hash no nome: não pode ficar em cache.
                                .cacheControl(CacheControl.noCache())
                                .body(indice)
                        : ServerResponse.status(404)
                                .contentType(MediaType.TEXT_PLAIN)
                                .body("A tela não foi construída: rode \"npm run construir\" em web/ (ou use o Vite, npm run dev)."));
    }

    /** "/contas/123" é endereço da tela; "/assets/app.js" ou "/favicon.svg" é arquivo. */
    static boolean semExtensao(ServerRequest pedido) {
        String caminho = pedido.path();
        return !caminho.substring(caminho.lastIndexOf('/') + 1).contains(".");
    }
}
