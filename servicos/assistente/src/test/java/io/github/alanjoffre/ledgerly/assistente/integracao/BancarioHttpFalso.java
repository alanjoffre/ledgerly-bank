package io.github.alanjoffre.ledgerly.assistente.integracao;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * O contas e o extrato dos testes de integração, num servidor HTTP de verdade: o teste diz as
 * contas de cada cliente e o JSON de cada conta, e confere o caminho e o token de cada pedido.
 * Conta de outro cliente responde 404, como o BOLA do serviço de verdade (ADR-0026).
 */
class BancarioHttpFalso {

    record Pedido(String caminho, String autorizacao) {}

    private static final Pattern CONTAS_DO_CLIENTE = Pattern.compile("/v1/clientes/([^/]+)/contas");
    private static final Pattern DA_CONTA = Pattern.compile("/v1/contas/([^/]+)/(saldo|extrato)");

    private final HttpServer servidor;
    private final Map<UUID, List<String>> contasJson = new ConcurrentHashMap<>();
    private final Map<String, String> respostasDaConta = new ConcurrentHashMap<>();
    private final List<Pedido> pedidos = new CopyOnWriteArrayList<>();
    private volatile boolean foraDoAr;

    BancarioHttpFalso() {
        try {
            servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        servidor.createContext("/", this::responder);
        servidor.start();
    }

    String url() {
        return "http://127.0.0.1:" + servidor.getAddress().getPort();
    }

    void zerar() {
        contasJson.clear();
        respostasDaConta.clear();
        pedidos.clear();
        foraDoAr = false;
    }

    /** Mais uma conta do cliente, com o saldo e o extrato (os itens em JSON). */
    UUID conta(UUID clienteId, String numero, String saldo, String itensJson) {
        UUID conta = UUID.randomUUID();
        contasJson.computeIfAbsent(clienteId, c -> new CopyOnWriteArrayList<>()).add("""
                {"id":"%s","clienteId":"%s","agencia":"0001","numero":"%s","situacao":"ATIVA"}"""
                .formatted(conta, clienteId, numero));
        respostasDaConta.put(conta + "/saldo", """
                {"contaId":"%s","saldo":"%s","moeda":"BRL","sequencia":3}""".formatted(conta, saldo));
        respostasDaConta.put(conta + "/extrato", """
                {"contaId":"%s","itens":%s,"proximaPagina":null}""".formatted(conta, itensJson));
        return conta;
    }

    void foraDoAr(boolean fora) {
        foraDoAr = fora;
    }

    List<Pedido> pedidos() {
        return List.copyOf(pedidos);
    }

    private void responder(HttpExchange troca) throws IOException {
        String caminho = troca.getRequestURI().getPath();
        pedidos.add(new Pedido(caminho, troca.getRequestHeaders().getFirst("Authorization")));
        String corpo = null;
        Matcher m;
        if ((m = CONTAS_DO_CLIENTE.matcher(caminho)).matches()) {
            List<String> contas = contasJson.get(UUID.fromString(m.group(1)));
            corpo = contas == null ? null : "[" + String.join(",", contas) + "]";
        } else if ((m = DA_CONTA.matcher(caminho)).matches()) {
            corpo = respostasDaConta.get(m.group(1) + "/" + m.group(2));
        }
        int status = foraDoAr ? 503 : corpo == null ? 404 : 200;
        byte[] bytes = (foraDoAr || corpo == null ? "{}" : corpo).getBytes(StandardCharsets.UTF_8);
        troca.getResponseHeaders().add("Content-Type", "application/json");
        troca.sendResponseHeaders(status, bytes.length);
        try (var saida = troca.getResponseBody()) {
            saida.write(bytes);
        }
    }

    void parar() {
        servidor.stop(0);
    }
}
