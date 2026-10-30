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
 * Um razão, um serviço de contas e um Keycloak <b>falsos</b>, num servidor HTTP do próprio JDK, para
 * os testes de integração do Pix: o Pix fala com eles por HTTP de verdade (clientes REST,
 * tempos-limite, códigos de status, descoberta OIDC), e o teste controla o que eles respondem.
 *
 * <p>O Keycloak falso publica a descoberta ({@code .well-known/openid-configuration}), a chave
 * pública (JWKS) e o endpoint de token: o Pix pede ali o próprio token (client credentials). O
 * razão e as contas falsos <b>recusam com 401</b> a chamada sem o token de serviço — é o que prova
 * que o filtro {@code @OidcClientFilter} está ligado.
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
    public static final AntifraudeFalso ANTIFRAUDE = new AntifraudeFalso();
    /** Quantos tokens o Pix pediu ao Keycloak falso (o token é reaproveitado até perto de vencer). */
    public static final AtomicInteger TOKENS_EMITIDOS = new AtomicInteger();

    @Override
    public Map<String, String> start() {
        try {
            servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        servidor.createContext("/v1/pix/", troca -> exigirToken(troca, RAZAO::atender));
        servidor.createContext("/v1/contas/", troca -> exigirToken(troca, CONTAS::atender));
        servidor.createContext("/v1/avaliacoes", troca -> exigirToken(troca, ANTIFRAUDE::atender));
        servidor.createContext("/realms/ledgerly/", ServicosFalsos::keycloak);
        servidor.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        servidor.start();
        String url = "http://127.0.0.1:" + servidor.getAddress().getPort();
        TokensPix.emissor(url + "/realms/ledgerly");
        return Map.of(
                "quarkus.rest-client.razao.url", url,
                "quarkus.rest-client.contas.url", url,
                "quarkus.rest-client.antifraude.url", url,
                "quarkus.oidc.auth-server-url", TokensPix.emissor(),
                // Tempo-limite curto nos testes: o caso "razão lento" não precisa esperar 3 s.
                "quarkus.rest-client.razao.read-timeout", "800");
    }

    @Override
    public void stop() {
        if (servidor != null) {
            servidor.stop(0);
        }
    }

    private interface Atendente {
        void atender(HttpExchange troca) throws IOException;
    }

    /** Como o razão e as contas de verdade: sem o token de um serviço, 401. */
    private static void exigirToken(HttpExchange troca, Atendente atendente) throws IOException {
        if (!TokensPix.ehDeServico(troca.getRequestHeaders().getFirst("Authorization"))) {
            responder(troca, 401, problema(401, "nao-autenticado", "sem token de serviço"));
            return;
        }
        atendente.atender(troca);
    }

    /** O Keycloak falso: descoberta, JWKS e o token do próprio Pix (client credentials). */
    private static void keycloak(HttpExchange troca) throws IOException {
        String emissor = TokensPix.emissor();
        String caminho = troca.getRequestURI().getPath();
        if (caminho.endsWith("/.well-known/openid-configuration")) {
            responder(troca, 200, Map.of(
                    "issuer", emissor,
                    "jwks_uri", emissor + "/protocol/openid-connect/certs",
                    "token_endpoint", emissor + "/protocol/openid-connect/token",
                    "id_token_signing_alg_values_supported", java.util.List.of("RS256")));
        } else if (caminho.endsWith("/protocol/openid-connect/certs")) {
            byte[] bytes = TokensPix.jwks().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(200, bytes.length);
            try (var saida = troca.getResponseBody()) {
                saida.write(bytes);
            }
        } else if (caminho.endsWith("/protocol/openid-connect/token")) {
            String formulario = new String(troca.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String basic = troca.getRequestHeaders().getFirst("Authorization");
            boolean credenciais = formulario.contains("client_secret=pix-segredo-local")
                    || (basic != null && new String(java.util.Base64.getDecoder().decode(basic.substring(6)),
                                    java.nio.charset.StandardCharsets.UTF_8)
                            .equals("ledgerly-pix:pix-segredo-local"));
            if (!formulario.contains("grant_type=client_credentials") || !credenciais) {
                responder(troca, 401, Map.of("error", "invalid_client"));
                return;
            }
            TOKENS_EMITIDOS.incrementAndGet();
            String token = TokensPix.token().nome("service-account-ledgerly-pix").papeis("servico").valor();
            responder(troca, 200, Map.of("access_token", token, "token_type", "Bearer", "expires_in", 300));
        } else {
            responder(troca, 404, problema(404, "nao-encontrado", caminho));
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
        /** A descrição que chegou em cada chave: é o texto que o extrato mostra. */
        private final Map<String, String> descricoes = new ConcurrentHashMap<>();
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
            descricoes.clear();
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

        public String descricao(String chave) {
            return descricoes.get(chave);
        }

        public synchronized int lancamentos() {
            return porChave.size();
        }

        void atender(HttpExchange troca) throws IOException {
            String caminho = troca.getRequestURI().getPath().substring("/v1/pix/".length());
            String chave = troca.getRequestHeaders().getFirst("Idempotency-Key");
            chamadas.computeIfAbsent(chave, k -> new AtomicInteger()).incrementAndGet();
            JsonNode corpo = JSON.readTree(troca.getRequestBody());
            descricoes.put(chave, corpo.path("descricao").asText());
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
            // Como o razão de verdade (ADR-0030): outra conta, só se a que pagou foi encerrada.
            if (caminho.equals("estornos") && !original.contaId().equals(conta)
                    && !encerradas.getOrDefault(original.contaId(), false)) {
                return "O estorno do envio %s só pode voltar para a conta que pagou (outra conta, só se ela foi encerrada)"
                        .formatted(envio);
            }
            if (porChave.values().stream().anyMatch(l -> envio.equals(l.envio()))) {
                return "O envio %s já teve a sua saída da Liquidação Pix".formatted(envio);
            }
            return null;
        }
    }

    /** O serviço de contas falso: titular, situação e CPF de cada conta. */
    public static final class ContasFalsas {

        private record Conta(String nome, String situacao, String cpf, UUID clienteId, java.time.Instant abertaEm) {}

        /** A abertura das contas dos testes: há mais de um ano, a não ser que o teste diga outra. */
        static final java.time.Instant ABERTURA_PADRAO = java.time.Instant.parse("2025-06-01T12:00:00Z");

        private final Map<UUID, Conta> contas = new ConcurrentHashMap<>();
        private volatile boolean foraDoAr;

        public void zerar() {
            contas.clear();
            foraDoAr = false;
        }

        public void conta(UUID id, String nome, String cpf, UUID clienteId) {
            contas.put(id, new Conta(nome, "ATIVA", cpf, clienteId, ABERTURA_PADRAO));
        }

        public void abertaEm(UUID id, java.time.Instant abertaEm) {
            contas.computeIfPresent(id, (k, c) -> new Conta(c.nome(), c.situacao(), c.cpf(), c.clienteId(), abertaEm));
        }

        public void bloquear(UUID id) {
            contas.computeIfPresent(id, (k, c) -> new Conta(c.nome(), "BLOQUEADA", c.cpf(), c.clienteId(), c.abertaEm()));
        }

        public void encerrar(UUID id) {
            contas.computeIfPresent(id, (k, c) -> new Conta(c.nome(), "ENCERRADA", c.cpf(), c.clienteId(), c.abertaEm()));
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
                        "contaId", partes[3],
                        "clienteId", conta.clienteId().toString(),
                        "nome", conta.nome(),
                        "situacao", conta.situacao(),
                        "abertaEm", conta.abertaEm().toString()));
                case "titularidade" -> {
                    String cpf = JSON.readTree(troca.getRequestBody()).path("cpf").asText().replaceAll("\\D", "");
                    responder(troca, 200, Map.of("confere", cpf.equals(conta.cpf())));
                }
                default -> responder(troca, 404, problema(404, "nao-encontrado", partes[4]));
            }
        }
    }

    /**
     * O antifraude falso: aprova tudo com nota 12, a não ser que o teste mande outra decisão, deixe
     * ele fora do ar ou lento. Guarda os pedidos, para o teste conferir o que o Pix mandou.
     */
    public static final class AntifraudeFalso {

        private volatile String decisao;
        private volatile int nota;
        private volatile java.util.List<String> motivos;
        private volatile boolean foraDoAr;
        private volatile long demoraMs;
        private final java.util.List<JsonNode> pedidos = new java.util.concurrent.CopyOnWriteArrayList<>();

        AntifraudeFalso() {
            zerar();
        }

        public void zerar() {
            decisao = "APROVAR";
            nota = 12;
            motivos = java.util.List.of();
            foraDoAr = false;
            demoraMs = 0;
            pedidos.clear();
        }

        /** As próximas avaliações respondem esta decisão. */
        public void decidir(String decisao, int nota, String... motivos) {
            this.decisao = decisao;
            this.nota = nota;
            this.motivos = java.util.List.of(motivos);
        }

        public void foraDoAr(boolean valor) {
            foraDoAr = valor;
        }

        /** Responde depois deste tempo (para passar do limite do Pix). */
        public void demorar(long ms) {
            demoraMs = ms;
        }

        public java.util.List<JsonNode> pedidos() {
            return java.util.List.copyOf(pedidos);
        }

        void atender(HttpExchange troca) throws IOException {
            JsonNode pedido = JSON.readTree(troca.getRequestBody());
            pedidos.add(pedido);
            if (foraDoAr) {
                responder(troca, 503, problema(503, "indisponivel", "fora do ar"));
                return;
            }
            if (demoraMs > 0) {
                try {
                    Thread.sleep(demoraMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            responder(troca, 200, Map.of(
                    "transferenciaId", pedido.path("transferenciaId").asText(),
                    "nota", nota,
                    "decisao", decisao,
                    "motivos", motivos.stream().map(t -> Map.of("codigo", "teste", "texto", t)).toList(),
                    "modelo", "risco-pix@teste"));
        }
    }
}
