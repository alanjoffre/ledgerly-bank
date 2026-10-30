package io.github.alanjoffre.ledgerly.assistente.adaptadores.saida.bancario;

import io.github.alanjoffre.ledgerly.assistente.aplicacao.porta.Bancario;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Os clientes HTTP do contas e do extrato, com tempos-limite curtos: a pessoa está esperando. */
@Configuration(proxyBeanMethods = false)
class ConfiguracaoBancario {

    @Bean
    Bancario bancario(
            RestClient.Builder builder,
            @Value("${ledgerly.contas.url}") String contas,
            @Value("${ledgerly.extrato.url}") String extrato,
            @Value("${ledgerly.bancario.tempo-conexao:2s}") Duration tempoConexao,
            @Value("${ledgerly.bancario.tempo-resposta:3s}") Duration tempoResposta) {
        HttpClient cliente = HttpClient.newBuilder().connectTimeout(tempoConexao).build();
        JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(cliente);
        fabrica.setReadTimeout(tempoResposta);
        return new HttpBancario(
                builder.clone().baseUrl(contas).requestFactory(fabrica).build(),
                builder.clone().baseUrl(extrato).requestFactory(fabrica).build());
    }
}
