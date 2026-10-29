package io.github.alanjoffre.ledgerly.extrato.adaptadores.saida.contas;

import io.github.alanjoffre.ledgerly.extrato.aplicacao.porta.DonoDaConta;
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

/** O cliente HTTP do contas, com tempos-limite e o token de serviço do extrato (ADR-0027). */
@Configuration(proxyBeanMethods = false)
class ConfiguracaoContas {

    @Bean
    DonoDaConta donoDaConta(
            RestClient.Builder builder,
            @Value("${ledgerly.contas.url}") String url,
            @Value("${ledgerly.contas.tempo-conexao:2s}") Duration tempoConexao,
            @Value("${ledgerly.contas.tempo-resposta:3s}") Duration tempoResposta,
            @Value("${ledgerly.contas.cache-de-donos:100000}") int limiteDoCache,
            OAuth2AuthorizedClientManager gerenteDeTokens,
            OAuth2AuthorizedClientService tokens) {
        HttpClient cliente = HttpClient.newBuilder().connectTimeout(tempoConexao).build();
        JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(cliente);
        fabrica.setReadTimeout(tempoResposta);
        RestClient http = builder.baseUrl(url).requestFactory(fabrica)
                .requestInterceptor(TokenDeServico.interceptador(gerenteDeTokens, tokens))
                .build();
        return new HttpDonoDaConta(http, limiteDoCache);
    }

    @Bean
    OAuth2AuthorizedClientManager gerenteDeTokens(
            ClientRegistrationRepository registros, OAuth2AuthorizedClientService tokens) {
        return TokenDeServico.gerente(registros, tokens);
    }
}
