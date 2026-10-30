package io.github.alanjoffre.ledgerly.assistente.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.alanjoffre.ledgerly.seguranca.teste.MockMvcComToken;
import io.github.alanjoffre.ledgerly.seguranca.teste.TokensDeTeste;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A avaliação do assistente contra o modelo de linguagem DE VERDADE (ADR-0037): as perguntas fixas
 * de {@code avaliacao/assistente/conjunto.json}, com o Ollama local, o pgvector de verdade e o
 * contas e o extrato falsos, com os dados sintéticos do conjunto. Mede a taxa de acerto e o tempo,
 * e grava o resultado em {@code avaliacao/assistente/resultados/}.
 *
 * <p>Não é um teste do build: o nome não termina em {@code Test} nem {@code IT}, e o CI não tem o
 * Ollama. Roda só quando chamada pelo nome (ver {@code avaliacao/README.md}):
 * <pre>
 *   ./mvnw -pl servicos/assistente test -Dtest=AvaliacaoDoAssistente -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * Outro modelo: {@code -Dassistente.linguagem.modelo=gemma3:4b}. Exigir um mínimo de acerto:
 * {@code -Davaliacao.minimo=0.8}.
 */
@SpringBootTest
@Import(AvaliacaoDoAssistente.Configuracao.class)
class AvaliacaoDoAssistente {

    private static final Path PASTA = Path.of("../../avaliacao/assistente");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode CONJUNTO = ler(PASTA.resolve("conjunto.json"));
    private static final BancarioHttpFalso BANCARIO = new BancarioHttpFalso();

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuracao {

        @Bean
        @ServiceConnection
        PostgreSQLContainer conteinerPgvector() {
            return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.7-pg18").asCompatibleSubstituteFor("postgres"));
        }

        @Bean
        JwtDecoder decodificador() {
            return TokensDeTeste.decodificador();
        }

        /** O "hoje" do conjunto: "o mês passado" e "os últimos 30 dias" dão sempre a mesma resposta. */
        @Bean
        @Primary
        Clock relogioDoConjunto() {
            return Clock.fixed(Instant.parse(CONJUNTO.path("hoje").asString()), ZoneOffset.UTC);
        }
    }

    record Resultado(String id, String categoria, String pergunta, String origem, String texto, List<String> fontes,
            List<String> falhas, long ms) {

        boolean acertou() {
            return falhas.isEmpty();
        }
    }

    @DynamicPropertySource
    static void bancario(DynamicPropertyRegistry propriedades) {
        propriedades.add("ledgerly.contas.url", BANCARIO::url);
        propriedades.add("ledgerly.extrato.url", BANCARIO::url);
    }

    @AfterAll
    static void pararBancario() {
        BANCARIO.parar();
    }

    @Autowired
    private WebApplicationContext contexto;

    @Value("${assistente.linguagem.modelo}")
    private String modelo;

    @Value("${assistente.linguagem.url}")
    private String urlDoOllama;

    @Value("${assistente.ajuda.modelo-de-vetores}")
    private String modeloDeVetores;

    @Test
    void avaliar() throws Exception {
        UUID ana = UUID.randomUUID();
        for (JsonNode conta : CONJUNTO.path("contas")) {
            BANCARIO.conta(ana, conta.path("numero").asString(), conta.path("saldo").asString(), conta.path("itens").toString());
        }
        String token = TokensDeTeste.cliente(ana);
        MockMvcTester http = MockMvcComToken.semPadrao(contexto);

        // A 1ª pergunta paga a carga do modelo na placa de vídeo e a indexação da ajuda: não entra na conta.
        perguntar(http, token, "Qual é o meu saldo?");

        List<Resultado> resultados = new ArrayList<>();
        for (JsonNode caso : CONJUNTO.path("perguntas")) {
            String pergunta = caso.path("pergunta").asString();
            long inicio = System.nanoTime();
            JsonNode resposta = perguntar(http, token, pergunta);
            long ms = (System.nanoTime() - inicio) / 1_000_000;
            String texto = resposta.path("texto").asString();
            String origem = resposta.path("origem").asString();
            List<String> fontes = new ArrayList<>();
            resposta.path("fontes").forEach(f -> fontes.add(f.asString()));
            Resultado r = new Resultado(caso.path("id").asString(), caso.path("categoria").asString(), pergunta, origem, texto,
                    fontes, ConferenciaDaAvaliacao.conferir(caso, origem, texto, fontes), ms);
            resultados.add(r);
            System.out.printf("%s %-24s %5d ms  %s%n", r.acertou() ? "✔" : "✘", r.id(), ms, r.acertou() ? "" : r.falhas());
        }

        gravar(resultados);
        double taxa = (double) resultados.stream().filter(Resultado::acertou).count() / resultados.size();
        assertThat(taxa).as("taxa de acerto").isGreaterThanOrEqualTo(Double.parseDouble(System.getProperty("avaliacao.minimo", "0")));
    }

    private static JsonNode perguntar(MockMvcTester http, String token, String pergunta) throws IOException {
        ObjectNode corpo = JSON.createObjectNode().put("pergunta", pergunta);
        var resultado = http.post().uri("/v1/assistente/perguntas")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo.toString())
                .exchange();
        assertThat(resultado).hasStatusOk();
        return JSON.readTree(resultado.getResponse().getContentAsString());
    }

    private void gravar(List<Resultado> resultados) throws IOException, InterruptedException {
        String dia = LocalDate.now(ZoneId.of("America/Sao_Paulo")).toString();
        String nome = dia + "-" + modelo.replaceAll("[^A-Za-z0-9.]+", "-");
        Path pasta = PASTA.resolve("resultados");
        Files.createDirectories(pasta);

        long acertos = resultados.stream().filter(Resultado::acertou).count();
        List<Long> tempos = resultados.stream().map(Resultado::ms).sorted().toList();
        Map<String, List<Resultado>> porCategoria = resultados.stream()
                .collect(Collectors.groupingBy(Resultado::categoria, LinkedHashMap::new, Collectors.toList()));
        String versao = versaoDoOllama();

        ObjectNode resumo = JSON.createObjectNode()
                .put("dia", dia).put("modelo", modelo).put("modeloDeVetores", modeloDeVetores).put("ollama", versao)
                .put("perguntas", resultados.size()).put("acertos", acertos)
                .put("p50Ms", percentil(tempos, 50)).put("p95Ms", percentil(tempos, 95)).put("maximoMs", tempos.getLast());
        ObjectNode categorias = resumo.putObject("porCategoria");
        porCategoria.forEach((c, lista) -> categorias.put(c, lista.stream().filter(Resultado::acertou).count() + "/" + lista.size()));
        ObjectNode origens = resumo.putObject("porOrigem");
        resultados.stream().collect(Collectors.groupingBy(Resultado::origem, LinkedHashMap::new, Collectors.counting()))
                .forEach(origens::put);
        resumo.set("resultados", JSON.valueToTree(resultados));
        Files.writeString(pasta.resolve(nome + ".json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(resumo) + "\n");

        StringBuilder md = new StringBuilder();
        md.append("# Avaliação do assistente — ").append(dia).append(", ").append(modelo).append("\n\n");
        md.append("Gerado por `AvaliacaoDoAssistente` (ADR-0037). Ollama ").append(versao).append(", vetores ")
                .append(modeloDeVetores).append(". Dados sintéticos do `conjunto.json`.\n\n");
        md.append("**Acertos: ").append(acertos).append(" de ").append(resultados.size()).append("** · tempo por pergunta: p50 ")
                .append(percentil(tempos, 50)).append(" ms, p95 ").append(percentil(tempos, 95)).append(" ms, máximo ")
                .append(tempos.getLast()).append(" ms\n\n");
        md.append("| Categoria | Acertos |\n| --- | --- |\n");
        porCategoria.forEach((c, lista) -> md.append("| ").append(c).append(" | ")
                .append(lista.stream().filter(Resultado::acertou).count()).append("/").append(lista.size()).append(" |\n"));
        md.append("\nOrigem das respostas: ").append(origens).append("\n\n");
        md.append("| | Pergunta | Origem | ms | Resposta | O que faltou |\n| --- | --- | --- | --- | --- | --- |\n");
        for (Resultado r : resultados) {
            md.append("| ").append(r.acertou() ? "✔" : "✘").append(" | ").append(celula(r.pergunta())).append(" | ")
                    .append(r.origem()).append(" | ").append(r.ms()).append(" | ").append(celula(r.texto())).append(" | ")
                    .append(celula(String.join("; ", r.falhas()))).append(" |\n");
        }
        Files.writeString(pasta.resolve(nome + ".md"), md.toString());
        System.out.printf("%nAcertos: %d de %d · p50 %d ms · p95 %d ms → %s%n", acertos, resultados.size(),
                percentil(tempos, 50), percentil(tempos, 95), pasta.resolve(nome + ".md").normalize());
    }

    private String versaoDoOllama() throws IOException, InterruptedException {
        try (HttpClient cliente = HttpClient.newHttpClient()) {
            String corpo = cliente.send(HttpRequest.newBuilder(URI.create(urlDoOllama + "/api/version")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            return JSON.readTree(corpo).path("version").asString("?");
        }
    }

    private static long percentil(List<Long> ordenados, int p) {
        int i = (int) Math.ceil(p / 100.0 * ordenados.size()) - 1;
        return ordenados.get(Math.max(0, i));
    }

    private static String celula(String texto) {
        return texto.replace("|", "\\|").replace("\n", " ");
    }

    private static JsonNode ler(Path arquivo) {
        try {
            return JSON.readTree(Files.readString(arquivo));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
