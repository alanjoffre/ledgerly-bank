package io.github.alanjoffre.ledgerly.carga;

import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.util.Objects;

/**
 * Quanto custa o salto pelo gateway (o BFF da Fase 4, ADR-0025)?
 *
 * <p>A mesma leitura — {@code GET} de uma conta, no serviço contas — de dois jeitos:
 *
 * <ul>
 *   <li>{@code alvo=direto}: direto no contas (8081), com o token de serviço;</li>
 *   <li>{@code alvo=bff}: pelo gateway do internet banking (8080), com o cookie de sessão de uma
 *       pessoa logada. O gateway acha a sessão, pega o token dela e repassa o pedido (TokenRelay).</li>
 * </ul>
 *
 * A diferença entre os dois é o preço do BFF: um salto de rede a mais na máquina, a sessão e o
 * repasse do token. O serviço faz o mesmo trabalho nos dois (lê a conta e confere quem pede).
 *
 * <p>Parâmetros ({@code -D}): {@code alvo}, {@code conta} (id), {@code cookie} (só no bff:
 * "LEDGERLY_IB=..."), {@code usuarios} (16), {@code segundos} (30), {@code emissor}. Quem prepara
 * tudo é o desempenho/carga/medir-gateway.sh.
 */
public final class GatewaySimulacao extends Simulation {

    private static final String ALVO = System.getProperty("alvo", "bff");
    private static final String CONTA = Objects.requireNonNull(System.getProperty("conta"), "informe -Dconta=<id>");
    private static final int USUARIOS = Integer.getInteger("usuarios", 16);
    private static final int SEGUNDOS = Integer.getInteger("segundos", 30);

    {
        HttpProtocolBuilder protocolo = switch (ALVO) {
            case "direto" -> http.baseUrl("http://localhost:8081")
                    .authorizationHeader("Bearer " + TokenDeServico.pedir(
                            System.getProperty("emissor", "http://localhost:18081/realms/ledgerly")));
            case "bff" -> http.baseUrl("http://localhost:8080")
                    .header("Cookie", Objects.requireNonNull(System.getProperty("cookie"), "informe -Dcookie=LEDGERLY_IB=..."));
            default -> throw new IllegalArgumentException("alvo desconhecido: " + ALVO);
        };
        String caminho = (ALVO.equals("bff") ? "/api" : "") + "/v1/contas/" + CONTA;
        setUp(scenario("leitura " + ALVO)
                        .exec(http("conta").get(caminho).check(status().is(200)))
                        .injectClosed(
                                rampConcurrentUsers(1).to(USUARIOS).during(5),
                                constantConcurrentUsers(USUARIOS).during(SEGUNDOS)))
                .protocols(protocolo.acceptHeader("application/json").disableWarmUp())
                .assertions(global().failedRequests().percent().lt(1.0));
    }
}
