package io.github.alanjoffre.ledgerly.gateway;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Um serviço de mentira atrás do gateway: responde 200 e guarda o último pedido que recebeu. */
final class ServicoFalso {

    /** {@code partes} e {@code tamanho}: os cabeçalhos Transfer-Encoding e Content-Length (ADR-0031). */
    record Pedido(
            String metodo, String caminho, String authorization, String cookie, String csrf, String corpo,
            String partes, String tamanho) {}

    private final String nome;
    private final HttpServer servidor;
    private final AtomicReference<Pedido> ultimo = new AtomicReference<>();

    ServicoFalso(String nome) {
        this.nome = nome;
        try {
            servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        servidor.createContext("/", troca -> {
            var cabecalhos = troca.getRequestHeaders();
            ultimo.set(new Pedido(
                    troca.getRequestMethod(),
                    troca.getRequestURI().toString(),
                    cabecalhos.getFirst("Authorization"),
                    cabecalhos.getFirst("Cookie"),
                    cabecalhos.getFirst("X-XSRF-TOKEN"),
                    new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    cabecalhos.getFirst("Transfer-Encoding"),
                    cabecalhos.getFirst("Content-Length")));
            byte[] resposta = ("{\"servico\":\"" + nome + "\"}").getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(200, resposta.length);
            troca.getResponseBody().write(resposta);
            troca.close();
        });
        servidor.start();
    }

    URI endereco() {
        return URI.create("http://127.0.0.1:" + servidor.getAddress().getPort());
    }

    Pedido ultimo() {
        return ultimo.get();
    }

    void esquecer() {
        ultimo.set(null);
    }

    static Map<String, ServicoFalso> tres() {
        return Map.of("contas", new ServicoFalso("contas"), "extrato", new ServicoFalso("extrato"), "pix", new ServicoFalso("pix"));
    }
}
