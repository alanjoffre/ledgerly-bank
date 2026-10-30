package io.github.alanjoffre.ledgerly.antifraude.integracao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Um Ollama de mentira: responde ao {@code POST /api/chat} como o de verdade, com o texto que o
 * teste mandar. Nos testes do CI não há modelo de linguagem (ADR-0037): o que se testa aqui é o
 * caminho em volta dele — o pedido que sai, o guardrail, o texto fixo e a gravação.
 */
public class OllamaFalso implements QuarkusTestResourceLifecycleManager {

    public static final OllamaFalso.Estado ESTADO = new Estado();
    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer servidor;

    public static final class Estado {
        private volatile String resposta = "";
        private volatile int status = 200;
        private final List<JsonNode> pedidos = new CopyOnWriteArrayList<>();

        public void zerar() {
            resposta = "";
            status = 200;
            pedidos.clear();
        }

        /** O texto que o "modelo" escreve nas próximas chamadas. */
        public void responder(String texto) {
            resposta = texto;
            status = 200;
        }

        public void foraDoAr() {
            status = 503;
        }

        public List<JsonNode> pedidos() {
            return List.copyOf(pedidos);
        }
    }

    @Override
    public Map<String, String> start() {
        try {
            servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        servidor.createContext("/api/chat", troca -> {
            JsonNode pedido = JSON.readTree(troca.getRequestBody());
            ESTADO.pedidos.add(pedido);
            byte[] corpo = ESTADO.status == 200
                    ? JSON.writeValueAsBytes(Map.of(
                            "model", pedido.path("model").asText(),
                            "created_at", "2026-10-29T17:00:00Z",
                            "message", Map.of("role", "assistant", "content", ESTADO.resposta),
                            "done", true,
                            "done_reason", "stop",
                            "prompt_eval_count", 120,
                            "eval_count", 60))
                    : "{\"error\":\"fora do ar\"}".getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(ESTADO.status, corpo.length);
            troca.getResponseBody().write(corpo);
            troca.close();
        });
        servidor.start();
        return Map.of(
                "antifraude.linguagem.url", "http://127.0.0.1:" + servidor.getAddress().getPort(),
                "antifraude.linguagem.modelo", "modelo-falso:1b");
    }

    @Override
    public void stop() {
        if (servidor != null) {
            servidor.stop(0);
        }
    }
}
