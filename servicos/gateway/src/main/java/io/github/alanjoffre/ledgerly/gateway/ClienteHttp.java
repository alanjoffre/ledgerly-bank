package io.github.alanjoffre.ledgerly.gateway;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.client.AbstractClientHttpRequest;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;

/**
 * O cliente HTTP com que o gateway chama os serviços: o do JDK, montado pelo Spring Boot, com um
 * contorno para uma corrida dentro do próprio JDK (ADR-0031).
 *
 * <p>Quando não sabe o tamanho do corpo, o gateway o repassa "em partes" ({@code Transfer-Encoding:
 * chunked}) — num GET, um corpo vazio. No cliente HTTP do JDK 25, um corpo em partes que termina
 * sem nenhum byte pode avisar "acabou" antes de o cliente terminar de se preparar para lê-lo, e o
 * pedido falha com {@code NullPointerException} ("bodySubscriber is null"). Com pouca CPU, como no
 * runner do CI, isso derrubava o teste do gateway em 2 de cada 3 rodadas.
 *
 * <p>O contorno: o corpo é lido para a memória antes de sair. Vazio, o pedido vai sem corpo; com
 * bytes, vai com {@code Content-Length}, sem partes. Os corpos que passam pelo gateway são JSON
 * pequenos (um Pix, um QR Code lido); a resposta continua passando direto, sem ir para a memória.
 */
@Configuration
class ClienteHttp {

    @Bean
    ClientHttpRequestFactory clienteHttpDosServicos(ClientHttpRequestFactoryBuilder<?> construtor, HttpClientSettings ajustes) {
        return new SemCorpoEmPartes(construtor.build(ajustes));
    }

    /** Envolve a fábrica do Boot: cada pedido lê o corpo inteiro antes de sair. */
    static final class SemCorpoEmPartes implements ClientHttpRequestFactory {

        private final ClientHttpRequestFactory fabrica;

        SemCorpoEmPartes(ClientHttpRequestFactory fabrica) {
            this.fabrica = fabrica;
        }

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod metodo) throws IOException {
            return new Pedido(fabrica.createRequest(uri, metodo));
        }
    }

    private static final class Pedido extends AbstractClientHttpRequest implements StreamingHttpOutputMessage {

        private final ClientHttpRequest pedido;
        private final ByteArrayOutputStream escrito = new ByteArrayOutputStream();
        private Body corpo;

        Pedido(ClientHttpRequest pedido) {
            this.pedido = pedido;
        }

        @Override
        public HttpMethod getMethod() {
            return pedido.getMethod();
        }

        @Override
        public URI getURI() {
            return pedido.getURI();
        }

        @Override
        public void setBody(Body corpo) {
            this.corpo = corpo;
        }

        @Override
        protected OutputStream getBodyInternal(HttpHeaders cabecalhos) {
            return escrito;
        }

        @Override
        protected ClientHttpResponse executeInternal(HttpHeaders cabecalhos) throws IOException {
            if (corpo != null) {
                corpo.writeTo(escrito);
            }
            byte[] bytes = escrito.toByteArray();
            pedido.getHeaders().putAll(cabecalhos);
            if (bytes.length > 0) {
                pedido.getHeaders().remove(HttpHeaders.TRANSFER_ENCODING);
                pedido.getHeaders().setContentLength(bytes.length);
                if (pedido instanceof StreamingHttpOutputMessage streaming) {
                    streaming.setBody(saida -> saida.write(bytes));
                } else {
                    pedido.getBody().write(bytes);
                }
            }
            return pedido.execute();
        }
    }
}
