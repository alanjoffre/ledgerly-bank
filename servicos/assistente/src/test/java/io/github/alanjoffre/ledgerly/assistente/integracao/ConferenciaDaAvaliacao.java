package io.github.alanjoffre.ledgerly.assistente.integracao;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/** Confere uma resposta do assistente contra um caso do conjunto de avaliação (ADR-0037). */
final class ConferenciaDaAvaliacao {

    private ConferenciaDaAvaliacao() {}

    /**
     * O que o caso pede: a origem permitida; cada item de "deve" presente (alternativas com "|");
     * nenhum de "naoPode"; e, se houver, a fonte. Sem diferença de maiúscula nem de acento.
     */
    static List<String> conferir(JsonNode caso, String origem, String texto, List<String> fontes) {
        List<String> falhas = new ArrayList<>();
        List<String> origens = new ArrayList<>();
        caso.path("origens").forEach(o -> origens.add(o.asString()));
        if (!origens.contains(origem)) {
            falhas.add("origem " + origem + " (esperada: " + String.join(" ou ", origens) + ")");
        }
        String normal = normalizar(texto);
        for (JsonNode deve : caso.path("deve")) {
            boolean algum = Arrays.stream(deve.asString().split("\\|")).anyMatch(a -> normal.contains(normalizar(a)));
            if (!algum) {
                falhas.add("faltou \"" + deve.asString() + "\"");
            }
        }
        for (JsonNode proibido : caso.path("naoPode")) {
            if (normal.contains(normalizar(proibido.asString()))) {
                falhas.add("trouxe \"" + proibido.asString() + "\"");
            }
        }
        if (caso.has("fonte")) {
            List<String> aceitas = Arrays.asList(caso.path("fonte").asString().split("\\|"));
            if (fontes.stream().noneMatch(aceitas::contains)) {
                falhas.add("sem a fonte \"" + caso.path("fonte").asString() + "\" (veio: " + fontes + ")");
            }
        }
        return falhas;
    }

    static String normalizar(String texto) {
        return Normalizer.normalize(texto.replace('\u00a0', ' '), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }
}
