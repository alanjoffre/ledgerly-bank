package io.github.alanjoffre.ledgerly.extrato.adaptadores.saida.contas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.ContasIndisponivelException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** O adaptador contra um contas falso (um servidor HTTP do próprio JDK): respostas e cache. */
@DisplayName("Dono da conta pelo contas (HTTP)")
class HttpDonoDaContaTest {

    private HttpServer servidor;
    private final Map<UUID, UUID> titulares = new ConcurrentHashMap<>();
    private final AtomicInteger chamadas = new AtomicInteger();
    private volatile int statusForcado;

    @BeforeEach
    void subir() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/v1/contas/", troca -> {
            chamadas.incrementAndGet();
            UUID conta = UUID.fromString(troca.getRequestURI().getPath().split("/")[3]);
            UUID cliente = titulares.get(conta);
            int status = statusForcado != 0 ? statusForcado : cliente == null ? 404 : 200;
            byte[] corpo = status == 200
                    ? "{\"contaId\":\"%s\",\"clienteId\":\"%s\"}".formatted(conta, cliente).getBytes(StandardCharsets.UTF_8)
                    : "{}".getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(status, corpo.length);
            troca.getResponseBody().write(corpo);
            troca.close();
        });
        servidor.start();
    }

    @AfterEach
    void descer() {
        servidor.stop(0);
    }

    private HttpDonoDaConta adaptador(int limiteDoCache) {
        String url = "http://127.0.0.1:" + servidor.getAddress().getPort();
        return new HttpDonoDaConta(RestClient.builder().baseUrl(url).build(), limiteDoCache);
    }

    @Test
    @DisplayName("a resposta do contas fica guardada: a segunda consulta não chama ninguém")
    void guardaODono() {
        UUID conta = UUID.randomUUID();
        UUID ana = UUID.randomUUID();
        titulares.put(conta, ana);
        HttpDonoDaConta donos = adaptador(10);
        assertThat(donos.clienteDe(conta)).contains(ana);
        assertThat(donos.clienteDe(conta)).contains(ana);
        assertThat(chamadas).hasValue(1);
    }

    @Test
    @DisplayName("conta que não existe: vazio, e não guardado (ela pode ser aberta no segundo seguinte)")
    void naoGuardaOVazio() {
        UUID conta = UUID.randomUUID();
        HttpDonoDaConta donos = adaptador(10);
        assertThat(donos.clienteDe(conta)).isEmpty();
        titulares.put(conta, UUID.randomUUID());
        assertThat(donos.clienteDe(conta)).isPresent();
        assertThat(donos.tamanhoDoCache()).isEqualTo(1);
    }

    @Test
    @DisplayName("o cache tem limite: passou dele, sai a conta usada há mais tempo")
    void limiteDoCache() {
        HttpDonoDaConta donos = adaptador(2);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        for (UUID conta : new UUID[] {a, b, c}) {
            titulares.put(conta, UUID.randomUUID());
        }
        donos.clienteDe(a);
        donos.clienteDe(b);
        donos.clienteDe(a); // a foi usada agora; b é a mais antiga
        donos.clienteDe(c);
        assertThat(donos.tamanhoDoCache()).isEqualTo(2);
        chamadas.set(0);
        donos.clienteDe(a);
        assertThat(chamadas).hasValue(0);
        donos.clienteDe(b);
        assertThat(chamadas).hasValue(1);
    }

    @Test
    @DisplayName("erro do contas ou contas fora do ar: indisponível (o controller responde 503)")
    void indisponivel() {
        HttpDonoDaConta donos = adaptador(10);
        statusForcado = 500;
        assertThatThrownBy(() -> donos.clienteDe(UUID.randomUUID()))
                .isInstanceOf(ContasIndisponivelException.class).hasMessageContaining("500");
        statusForcado = 403;
        assertThatThrownBy(() -> donos.clienteDe(UUID.randomUUID())).isInstanceOf(ContasIndisponivelException.class);
        servidor.stop(0);
        assertThatThrownBy(() -> donos.clienteDe(UUID.randomUUID())).isInstanceOf(ContasIndisponivelException.class);
    }
}
