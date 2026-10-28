package io.github.alanjoffre.ledgerly.pix.integracao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Um razão e um serviço de contas <b>falsos</b>, num servidor HTTP do próprio JDK, para os testes de
 * integração do Pix: o Pix fala com eles por HTTP de verdade (clientes REST, tempos-limite,
 * códigos de status), e o teste controla o que eles respondem.
 *
 * <p>O razão falso segue o contrato do verdadeiro, inclusive a <b>idempotência</b>: a mesma
 * {@code Idempotency-Key} devolve 200 com o lançamento original, sem mover o dinheiro de novo — e
 * a conta Liquidação Pix não fica negativa. É o que deixa os testes provarem "nenhum centavo duas
 * vezes" mesmo com falhas e repetições.
 */
public class ServicosFalsos implements QuarkusTestResourceLifecycleManager {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static HttpServer servidor;

    public static final RazaoFalso RAZAO = new RazaoFalso();
    public static final ContasFalsas CONTAS = new ContasFalsas();

    @Override
    public Map<String, String> start() {
        try {
            servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        servidor.createContext("/v1/pix/", RAZAO::atender);
        servidor.createContext("/v1/contas/", CONTAS::atender);
        servidor.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        servidor.start();
        String url = "http://127.0.0.1:" + servidor.getAddress().getPort();
        return Map.of(
                "quarkus.rest-client.razao.url", url,
                "quarkus.rest-client.contas.url", url,
                // Tempo-limite curto nos testes: o caso "razão lento" não precisa esperar 3 s.
                "quarkus.rest-client.razao.read-timeout", "800");
    }

    @Override
    public void stop() {
        if (servidor != null) {
            servidor.stop(0);
        }
    }

    static void responder(HttpExchange troca, int status, Object corpo) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(corpo);
        troca.getResponseHeaders().add("Content-Type", status >= 400 ? "application/problem+json" : "application/json");
        troca.sendResponseHeaders(status, bytes.length);
        try (var saida = troca.getResponseBody()) {
            saida.write(bytes);
        }
    }

    static Map<String, Object> problema(int status, String codigo, String detalhe) {
        return Map.of("type", "urn:ledgerly:erro:" + codigo, "status", status, "detail", detalhe);
    }

    /** O razão falso: saldos em memória, a Liquidação Pix, idempotência e falhas sob encomenda. */
    public static final class RazaoFalso {

        /** {@code envio}: no recebimento e no estorno, o envio de onde o dinheiro sai (ADR-0019). */
        private record Lancamento(UUID id, String caminho, UUID contaId, BigDecimal valor, UUID envio) {}

        private final Map<UUID, BigDecimal> saldos = new HashMap<>();
        private final Map<UUID, Boolean> encerradas = new HashMap<>();
        private final Map<String, Lancamento> porChave = new HashMap<>();
        private final Map<String, AtomicInteger> chamadas = new ConcurrentHashMap<>();
        private BigDecimal liquidacao = BigDecimal.ZERO;
        private int indisponivelPorVezes;
        private String caminhoIndisponivel = "";
        private long atrasoAposAplicarMs;
        private String caminhoAtrasado = "";

        public synchronized void zerar() {
            saldos.clear();
            encerradas.clear();
            porChave.clear();
            chamadas.clear();
            liquidacao = BigDecimal.ZERO;
            indisponivelPorVezes = 0;
            atrasoAposAplicarMs = 0;
        }

        public synchronized void conta(UUID id, String saldo) {
            saldos.put(id, new BigDecimal(saldo));
        }

        public synchronized void encerrar(UUID id) {
            encerradas.put(id, true);
        }

        public synchronized BigDecimal saldo(UUID id) {
            return saldos.get(id);
        }

        public synchronized BigDecimal liquidacao() {
            return liquidacao;
        }

        /** Simula um envio que já aconteceu (para o teste de "o serviço morreu no meio"). */
        public synchronized UUID jaEnviado(String chave, UUID conta, String valor) {
            UUID id = UUID.randomUUID();
            BigDecimal v = new BigDecimal(valor);
            saldos.merge(conta, v.negate(), BigDecimal::add);
            liquidacao = liquidacao.add(v);
            porChave.put(chave, new Lancamento(id, "envios", conta, v, null));
            return id;
        }

        /** As próximas {@code vezes} chamadas a {@code caminho} ("envios", "recebimentos"...) respondem 503. */
        public synchronized void indisponivel(String caminho, int vezes) {
            caminhoIndisponivel = caminho;
            indisponivelPorVezes = vezes;
        }

        /**
         * A próxima chamada a {@code caminho} MOVE o dinheiro e só então demora a responder — mais
         * que o tempo-limite do Pix. É o caso mais traiçoeiro: para o Pix, "sem resposta"; no razão,
         * feito.
         */
        public synchronized void lentoDepoisDeAplicar(String caminho, long atrasoMs) {
            caminhoAtrasado = caminho;
            atrasoAposAplicarMs = atrasoMs;
        }

        public int chamadas(String chave) {
            return chamadas.getOrDefault(chave, new AtomicInteger()).get();
        }

        public synchronized int lancamentos() {
            return porChave.size();
        }

        void atender(HttpExchange troca) throws IOException {
            String caminho = troca.getRequestURI().getPath().substring("/v1/pix/".length());
            String chave = troca.getRequestHeaders().getFirst("Idempotency-Key");
            chamadas.computeIfAbsent(chave, k -> new AtomicInteger()).incrementAndGet();
            JsonNode corpo = JSON.readTree(troca.getRequestBody());
            UUID conta = UUID.fromString(corpo.path("contaId").asText());
            BigDecimal valor = new BigDecimal(corpo.path("valor").asText());
            String envioInformado = corpo.path("lancamentoEnvioId").asText("");
            UUID envio = envioInformado.isBlank() ? null : UUID.fromString(envioInformado);

            long atraso;
            Object[] resposta;
            synchronized (this) {
                if (caminho.equals(caminhoIndisponivel) && indisponivelPorVezes > 0) {
                    indisponivelPorVezes--;
                    resposta = new Object[] {503, problema(503, "indisponivel", "fora do ar")};
                    atraso = 0;
                } else {
                    resposta = aplicar(caminho, chave, conta, valor, envio);
                    atraso = caminho.equals(caminhoAtrasado) ? atrasoAposAplicarMs : 0;
                    if (atraso > 0) {
                        atrasoAposAplicarMs = 0;
                    }
                }
            }
            if (atraso > 0) {
                try {
                    Thread.sleep(atraso);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            responder(troca, (int) resposta[0], resposta[1]);
        }

        private Object[] aplicar(String caminho, String chave, UUID conta, BigDecimal valor, UUID envio) {
            Lancamento existente = porChave.get(chave);
            if (existente != null) {
                return new Object[] {200, Map.of("id", existente.id().toString())};
            }
            if (!saldos.containsKey(conta)) {
                return new Object[] {404, problema(404, "conta-nao-encontrada", "Conta não encontrada")};
            }
            switch (caminho) {
                case "envios" -> {
                    if (saldos.get(conta).compareTo(valor) < 0) {
                        return new Object[] {422, problema(422, "saldo-insuficiente", "Saldo insuficiente na conta " + conta)};
                    }
                    saldos.merge(conta, valor.negate(), BigDecimal::add);
                    liquidacao = liquidacao.add(valor);
                }
                case "recebimentos", "estornos" -> {
                    if (encerradas.getOrDefault(conta, false)) {
                        return new Object[] {422, problema(422, "conta-inativa", "A conta %s está encerrada".formatted(conta))};
                    }
                    // Como o razão de verdade: a saída aponta para o envio, com o mesmo valor, uma vez só.
                    if (envio == null) {
                        return new Object[] {400, problema(400, "requisicao-invalida", "lancamentoEnvioId é obrigatório")};
                    }
                    String recusa = recusaDaSaida(caminho, conta, valor, envio);
                    if (recusa != null) {
                        return new Object[] {422, problema(422, "envio-pix-invalido", recusa)};
                    }
                    if (liquidacao.compareTo(valor) < 0) {
                        return new Object[] {422, problema(422, "saldo-insuficiente", "Liquidação Pix sem saldo")};
                    }
                    liquidacao = liquidacao.subtract(valor);
                    saldos.merge(conta, valor, BigDecimal::add);
                }
                default -> {
                    return new Object[] {404, problema(404, "nao-encontrado", caminho)};
                }
            }
            Lancamento novo = new Lancamento(UUID.randomUUID(), caminho, conta, valor, envio);
            porChave.put(chave, novo);
            return new Object[] {201, Map.of("id", novo.id().toString())};
        }

        private String recusaDaSaida(String caminho, UUID conta, BigDecimal valor, UUID envio) {
            Lancamento original = porChave.values().stream()
                    .filter(l -> l.id().equals(envio) && l.caminho().equals("envios"))
                    .findFirst()
                    .orElse(null);
            if (original == null) {
                return "O lançamento %s não é um envio de Pix".formatted(envio);
            }
            if (original.valor().compareTo(valor) != 0) {
                return "O envio %s foi de %s".formatted(envio, original.valor());
            }
            if (caminho.equals("estornos") && !original.contaId().equals(conta)) {
                return "O estorno do envio %s só pode voltar para a conta que pagou".formatted(envio);
            }
            if (porChave.values().stream().anyMatch(l -> envio.equals(l.envio()))) {
                return "O envio %s já teve a sua saída da Liquidação Pix".formatted(envio);
            }
            return null;
        }
    }

    /** O serviço de contas falso: titular, situação e CPF de cada conta. */
    public static final class ContasFalsas {

        private record Conta(String nome, String situacao, String cpf) {}

        private final Map<UUID, Conta> contas = new ConcurrentHashMap<>();
        private volatile boolean foraDoAr;

        public void zerar() {
            contas.clear();
            foraDoAr = false;
        }

        public void conta(UUID id, String nome, String cpf) {
            contas.put(id, new Conta(nome, "ATIVA", cpf));
        }

        public void bloquear(UUID id) {
            contas.computeIfPresent(id, (k, c) -> new Conta(c.nome(), "BLOQUEADA", c.cpf()));
        }

        public void foraDoAr(boolean valor) {
            foraDoAr = valor;
        }

        void atender(HttpExchange troca) throws IOException {
            if (foraDoAr) {
                responder(troca, 503, problema(503, "razao-indisponivel", "fora do ar"));
                return;
            }
            String[] partes = troca.getRequestURI().getPath().split("/"); // "", v1, contas, {id}, acao
            Conta conta = contas.get(UUID.fromString(partes[3]));
            if (conta == null) {
                responder(troca, 404, problema(404, "conta-nao-encontrada", "Conta não encontrada"));
                return;
            }
            switch (partes[4]) {
                case "titular" -> responder(troca, 200, Map.of(
                        "contaId", partes[3], "nome", conta.nome(), "situacao", conta.situacao()));
                case "titularidade" -> {
                    String cpf = JSON.readTree(troca.getRequestBody()).path("cpf").asText().replaceAll("\\D", "");
                    responder(troca, 200, Map.of("confere", cpf.equals(conta.cpf())));
                }
                default -> responder(troca, 404, problema(404, "nao-encontrado", partes[4]));
            }
        }
    }
}
