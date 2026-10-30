package io.github.alanjoffre.ledgerly.assistente.dominio;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A trava de entrada (ADR-0036): o que nem chega ao modelo de linguagem.
 *
 * <p>Pega o óbvio — texto vazio, longo demais, e as frases de quem tenta trocar as instruções do
 * assistente ("ignore as instruções anteriores", "você agora é…"). Não pega tudo: a defesa que vale
 * de verdade está atrás, nas ferramentas (só leem, e só as contas do dono do token) e na trava de
 * saída. Pergunta de outro assunto passa daqui; quem recusa é o modelo, pelas instruções, e as
 * evals medem quantas vezes ele recusa certo.
 */
public final class GuardrailDeEntrada {

    public static final int TAMANHO_MAXIMO = 500;

    private static final List<Pattern> TROCA_DE_INSTRUCOES = List.of(
            "(ignore|ignorar|esqueca|esquecer|desconsidere|desconsiderar)\\b.{0,30}\\b(instrucoes|regras|ordens|prompt)",
            "instrucoes (anteriores|do sistema|originais)",
            "(prompt|mensagem) do sistema",
            "system prompt",
            "voce (agora )?(e|sera|vai ser) (um|uma|o|a) ",
            "(finja|fingir|aja como|age como|faca de conta)",
            "modo (desenvolvedor|dev|sem restricoes|irrestrito)",
            "jailbreak|\\bDAN\\b")
            .stream().map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE)).toList();

    private GuardrailDeEntrada() {}

    /** Por que a pergunta não segue; vazio quando segue. */
    public static Optional<String> problema(String pergunta) {
        if (pergunta == null || pergunta.isBlank()) {
            return Optional.of("pergunta vazia");
        }
        if (pergunta.length() > TAMANHO_MAXIMO) {
            return Optional.of("pergunta com %d caracteres (máximo %d)".formatted(pergunta.length(), TAMANHO_MAXIMO));
        }
        String simples = semAcento(pergunta);
        return TROCA_DE_INSTRUCOES.stream()
                .filter(p -> p.matcher(simples).find())
                .findFirst()
                .map(p -> "tentativa de trocar as instruções");
    }

    private static String semAcento(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
