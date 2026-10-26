package io.github.alanjoffre.ledgerly.contas.adaptadores.saida.razao;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** O cliente HTTP do razão, com endereço e tempos-limite vindos da configuração. */
@Configuration(proxyBeanMethods = false)
class ConfiguracaoRazao {

    @Bean
    RestClient razaoRestClient(
            RestClient.Builder builder,
            @Value("${ledgerly.razao.url}") String url,
            @Value("${ledgerly.razao.tempo-conexao:2s}") Duration tempoConexao,
            @Value("${ledgerly.razao.tempo-resposta:5s}") Duration tempoResposta) {
        HttpClient cliente = HttpClient.newBuilder().connectTimeout(tempoConexao).build();
        JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(cliente);
        fabrica.setReadTimeout(tempoResposta);
        return builder.baseUrl(url).requestFactory(fabrica).build();
    }
}
