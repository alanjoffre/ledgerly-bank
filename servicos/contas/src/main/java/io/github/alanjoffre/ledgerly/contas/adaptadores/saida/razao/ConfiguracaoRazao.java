package io.github.alanjoffre.ledgerly.contas.adaptadores.saida.razao;

import io.github.alanjoffre.ledgerly.seguranca.TokenDeServico;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.client.RestClient;

/**
 * O cliente HTTP do razão, com endereço e tempos-limite vindos da configuração, e o token de
 * serviço do contas em todo pedido (ADR-0027): o razão só atende o papel {@code servico}.
 */
@Configuration(proxyBeanMethods = false)
class ConfiguracaoRazao {

    @Bean
    RestClient razaoRestClient(
            RestClient.Builder builder,
            @Value("${ledgerly.razao.url}") String url,
            @Value("${ledgerly.razao.tempo-conexao:2s}") Duration tempoConexao,
            @Value("${ledgerly.razao.tempo-resposta:5s}") Duration tempoResposta,
            OAuth2AuthorizedClientManager gerenteDeTokens,
            OAuth2AuthorizedClientService tokens) {
        HttpClient cliente = HttpClient.newBuilder().connectTimeout(tempoConexao).build();
        JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(cliente);
        fabrica.setReadTimeout(tempoResposta);
        return builder.baseUrl(url).requestFactory(fabrica)
                .requestInterceptor(TokenDeServico.interceptador(gerenteDeTokens, tokens))
                .build();
    }

    @Bean
    OAuth2AuthorizedClientManager gerenteDeTokens(
            ClientRegistrationRepository registros, OAuth2AuthorizedClientService tokens) {
        return TokenDeServico.gerente(registros, tokens);
    }
}
