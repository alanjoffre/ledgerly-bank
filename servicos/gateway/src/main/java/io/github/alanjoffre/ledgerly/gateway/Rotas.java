package io.github.alanjoffre.ledgerly.gateway;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.removeRequestHeader;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.stripPrefix;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.filter.TokenRelayFilterFunctions.tokenRelay;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.web.servlet.function.RequestPredicates.GET;
import static org.springframework.web.servlet.function.RequestPredicates.POST;
import static org.springframework.web.servlet.function.RequestPredicates.path;

import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * As rotas: {@code /api/v1/...} vai ao serviço dono do caminho, sem o {@code /api} e com o token.
 *
 * <p>Só contas, extrato, Pix e a leitura de uma avaliação do antifraude têm rota. O razão não tem
 * (ADR-0027): só outro serviço, com token de serviço, fala com ele. Do antifraude, só o
 * {@code GET} de uma avaliação e da explicação dela passa (a analista lê por que o Pix foi retido); pedir uma
 * avaliação é coisa do Pix, e nem chega ao serviço (ADR-0032). Do assistente, só a pergunta
 * ({@code POST}); quem confere que é um cliente é o próprio serviço (ADR-0036). A ordem importa — o extrato e o Pix respondem por caminhos dentro de
 * {@code /v1/contas/...}, então vêm antes da rota geral do contas.
 *
 * <p>O cookie de sessão e o cabeçalho de CSRF não seguem para os serviços: lá fora eles não
 * significam nada, e não há por que espalhar a sessão.
 */
@Configuration
class Rotas {

    @Bean
    RouterFunction<ServerResponse> rotasDoBff(PropriedadesDoBff bff) {
        var servicos = bff.servicos();
        return rota("extrato", servicos.extrato(),
                        path("/api/v1/contas/{id}/extrato").or(path("/api/v1/contas/{id}/saldo")))
                .and(rota("pix", servicos.pix(), path("/api/v1/transferencias/**")
                        .or(path("/api/v1/chaves/**"))
                        .or(path("/api/v1/contas/{id}/chaves"))
                        .or(path("/api/v1/qrcodes/**"))))
                .and(rota("antifraude", servicos.antifraude(), GET("/api/v1/avaliacoes/{transferenciaId}")
                        .or(GET("/api/v1/avaliacoes/{transferenciaId}/explicacao"))))
                .and(rota("assistente", servicos.assistente(), POST("/api/v1/assistente/perguntas")))
                .and(rota("contas", servicos.contas(), path("/api/v1/clientes/**").or(path("/api/v1/contas/**"))));
    }

    private static RouterFunction<ServerResponse> rota(String id, URI destino, RequestPredicate caminhos) {
        return route(id)
                .route(caminhos, http())
                .before(uri(destino))
                .before(stripPrefix(1))
                .before(removeRequestHeader("Cookie"))
                .before(removeRequestHeader("X-XSRF-TOKEN"))
                .filter(tokenRelay())
                .build();
    }
}
