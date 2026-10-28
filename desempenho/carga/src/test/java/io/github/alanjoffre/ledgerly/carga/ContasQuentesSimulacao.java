package io.github.alanjoffre.ledgerly.carga;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Teste de carga das <b>contas quentes</b> do razão (Fase 3, ADR-0019).
 *
 * <p>Uma conta quente é uma conta que quase todo lançamento toca. No razão são duas: o
 * <b>Caixa</b> (todo depósito e todo saque) e a <b>Liquidação Pix</b> (todo Pix). Como cada
 * lançamento trava as suas contas ({@code SELECT ... FOR UPDATE}, ADR-0009), dois depósitos em
 * contas de clientes diferentes ainda disputam a mesma linha: a do Caixa. Os cenários:
 *
 * <ul>
 *   <li>{@code depositos} — todo pedido passa pelo Caixa;</li>
 *   <li>{@code pix-envios} — todo pedido passa pela Liquidação Pix;</li>
 *   <li>{@code transferencias} — entre clientes sorteados, sem conta quente: a referência do que o
 *       razão aguenta quando os pedidos não disputam a mesma linha.</li>
 * </ul>
 *
 * <p><b>Modelo fechado</b> ({@code constantConcurrentUsers}): há sempre N pedidos em andamento;
 * quando um termina, outro começa. É o jeito de medir a vazão máxima — quantos pedidos por segundo
 * o serviço entrega com N clientes martelando.
 *
 * <p>Parâmetros (todos com {@code -D}): {@code cenario}, {@code usuarios} (64), {@code segundos}
 * (30), {@code contas} (2000), {@code razao} (http://localhost:8082).
 */
public final class ContasQuentesSimulacao extends Simulation {

    private static final String RAZAO = System.getProperty("razao", "http://localhost:8082");
    private static final String CENARIO = System.getProperty("cenario", "depositos");
    private static final int USUARIOS = Integer.getInteger("usuarios", 64);
    private static final int SEGUNDOS = Integer.getInteger("segundos", 30);
    private static final int CONTAS = Integer.getInteger("contas", 2000);

    /** As contas de cliente abertas no {@link #before()}; só leitura durante a carga. */
    private static final List<String> contas = new ArrayList<>();

    private final HttpProtocolBuilder protocolo = http.baseUrl(RAZAO)
            .contentTypeHeader("application/json")
            .acceptHeader("application/json")
            // Uma conexão por usuário virtual, como N clientes de verdade.
            .disableWarmUp();

    {
        ChainBuilder pedido = switch (CENARIO) {
            case "depositos" -> movimento("depósito", "/v1/depositos");
            case "pix-envios" -> movimento("envio de Pix", "/v1/pix/envios");
            case "transferencias" -> transferencia();
            default -> throw new IllegalArgumentException("cenário desconhecido: " + CENARIO);
        };
        setUp(scenario(CENARIO)
                        .exec(pedido)
                        .injectClosed(
                                rampConcurrentUsers(1).to(USUARIOS).during(5),
                                constantConcurrentUsers(USUARIOS).during(SEGUNDOS)))
                .protocols(protocolo)
                // Mais de 1% de erro invalida a medição: a vazão de pedidos recusados não conta.
                .assertions(global().failedRequests().percent().lt(1.0));
    }

    /** Depósito ou envio de Pix: uma conta de cliente sorteada + a conta quente. */
    private static ChainBuilder movimento(String nome, String caminho) {
        return exec(sessao -> sessao.set("conta", sortear()).set("chave", UUID.randomUUID().toString()))
                .exec(http(nome)
                        .post(caminho)
                        .header("Idempotency-Key", "#{chave}")
                        .body(StringBody("{\"contaId\":\"#{conta}\",\"valor\":\"1.00\",\"descricao\":\"carga\"}"))
                        .check(status().is(201)));
    }

    /** Transferência entre dois clientes sorteados: nenhuma conta quente no caminho. */
    private static ChainBuilder transferencia() {
        return exec(sessao -> {
                    String origem = sortear();
                    String destino = sortear();
                    while (destino.equals(origem)) {
                        destino = sortear();
                    }
                    return sessao.set("origem", origem)
                            .set("destino", destino)
                            .set("chave", UUID.randomUUID().toString());
                })
                .exec(http("transferência")
                        .post("/v1/transferencias")
                        .header("Idempotency-Key", "#{chave}")
                        .body(StringBody(
                                "{\"origemId\":\"#{origem}\",\"destinoId\":\"#{destino}\",\"valor\":\"1.00\",\"descricao\":\"carga\"}"))
                        .check(status().is(201)));
    }

    private static String sortear() {
        return contas.get(ThreadLocalRandom.current().nextInt(contas.size()));
    }

    /**
     * Antes da carga: abre as contas no razão e, nos cenários que tiram dinheiro do cliente, põe
     * saldo nelas. Feito com o HttpClient do próprio Java, em virtual threads — fora da medição.
     */
    @Override
    public void before() {
        HttpClient cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        boolean precisaDeSaldo = !CENARIO.equals("depositos");
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> abertas = new ArrayList<>();
            for (int i = 0; i < CONTAS; i++) {
                abertas.add(executor.submit(() -> {
                    String id = UUID.randomUUID().toString();
                    enviar(cliente, "PUT", "/v1/contas/" + id, null, "{\"moeda\":\"BRL\"}");
                    if (precisaDeSaldo) {
                        enviar(cliente, "POST", "/v1/depositos", UUID.randomUUID().toString(),
                                "{\"contaId\":\"" + id + "\",\"valor\":\"1000000.00\",\"descricao\":\"saldo da carga\"}");
                    }
                    return id;
                }));
            }
            for (Future<String> aberta : abertas) {
                contas.add(aberta.get());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Não foi possível preparar as contas da carga em " + RAZAO, e);
        }
        System.out.printf("Carga '%s': %d contas prontas, %d usuários por %d s%n", CENARIO, contas.size(), USUARIOS, SEGUNDOS);
    }

    /** Depois da carga: o razão continua íntegro? (balanço fecha, saldos = soma das partidas). */
    @Override
    public void after() {
        try {
            var resposta = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(RAZAO + "/v1/integridade")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            System.out.println("Integridade do razão depois da carga: " + resposta.body());
        } catch (Exception e) {
            System.out.println("Não foi possível conferir a integridade: " + e.getMessage());
        }
    }

    private static void enviar(HttpClient cliente, String metodo, String caminho, String chave, String corpo)
            throws Exception {
        var pedido = HttpRequest.newBuilder(URI.create(RAZAO + caminho))
                .header("Content-Type", "application/json")
                .method(metodo, HttpRequest.BodyPublishers.ofString(corpo));
        if (chave != null) {
            pedido.header("Idempotency-Key", chave);
        }
        var resposta = cliente.send(pedido.build(), HttpResponse.BodyHandlers.discarding());
        if (resposta.statusCode() >= 300) {
            throw new IllegalStateException(metodo + " " + caminho + " respondeu " + resposta.statusCode());
        }
    }
}
