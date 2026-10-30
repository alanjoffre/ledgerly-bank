package io.github.alanjoffre.ledgerly.assistente.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.alanjoffre.ledgerly.assistente.dominio.Resposta;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A régua da avaliação e o próprio conjunto: roda no build, sem o Ollama (ADR-0037). */
@DisplayName("Avaliação do assistente: a régua e o conjunto")
class ConferenciaDaAvaliacaoTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode caso(String json) {
        return JSON.readTree(json);
    }

    @Test
    @DisplayName("acerto: a origem permitida, cada \"deve\" (com alternativas), nenhum \"naoPode\" e a fonte")
    void acerto() {
        JsonNode caso = caso("""
                {"deve": ["6.319,70", "saldo|disponível"], "naoPode": ["3.500"], "origens": ["MODELO_DE_LINGUAGEM"],
                 "fonte": "Extrato e saldo|Pix retido para análise"}""");
        assertThat(ConferenciaDaAvaliacao.conferir(caso, "MODELO_DE_LINGUAGEM", "Você tem R$ 6.319,70 DISPONÍVEL.",
                List.of("Pix retido para análise"))).isEmpty();
    }

    @Test
    @DisplayName("cada problema vira uma falha, com o que faltou ou sobrou")
    void falhas() {
        JsonNode caso = caso("""
                {"deve": ["6.319,70"], "naoPode": ["3.500"], "origens": ["RECUSA"], "fonte": "Chaves Pix"}""");
        assertThat(ConferenciaDaAvaliacao.conferir(caso, "RESERVA", "Você tem R$ 3.500,00.", List.of()))
                .containsExactly("origem RESERVA (esperada: RECUSA)", "faltou \"6.319,70\"", "trouxe \"3.500\"",
                        "sem a fonte \"Chaves Pix\" (veio: [])");
    }

    @Test
    @DisplayName("sem diferença de acento nem de maiúscula: \"Não\" vale \"nao\"")
    void normalizar() {
        assertThat(ConferenciaDaAvaliacao.normalizar("NÃO saiu, Análise")).isEqualTo("nao saiu, analise");
    }

    @Test
    @DisplayName("o conjunto: 30 perguntas com id único, origens que existem, e saldos que fecham com o extrato")
    void conjunto() throws IOException {
        JsonNode conjunto = JSON.readTree(Files.readString(Path.of("../../avaliacao/assistente/conjunto.json")));

        Set<String> ids = new HashSet<>();
        Set<String> origensQueExistem = new HashSet<>();
        Arrays.stream(Resposta.Origem.values()).forEach(o -> origensQueExistem.add(o.name()));
        List<String> origens = new ArrayList<>();
        for (JsonNode caso : conjunto.path("perguntas")) {
            assertThat(ids.add(caso.path("id").asString())).as("id repetido: %s", caso.path("id")).isTrue();
            caso.path("origens").forEach(o -> origens.add(o.asString()));
        }
        assertThat(ids).hasSize(30);
        assertThat(origensQueExistem).containsAll(origens);

        for (JsonNode conta : conjunto.path("contas")) {
            JsonNode itens = conta.path("itens");
            assertThat(new BigDecimal(itens.get(0).path("saldoApos").asString()))
                    .as("o saldo da conta %s é o do lançamento mais novo", conta.path("numero"))
                    .isEqualByComparingTo(conta.path("saldo").asString());
        }
    }
}
