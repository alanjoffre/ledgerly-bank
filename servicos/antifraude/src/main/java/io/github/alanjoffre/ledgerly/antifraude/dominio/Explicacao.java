package io.github.alanjoffre.ledgerly.antifraude.dominio;

import java.time.Instant;
import java.util.Objects;

/**
 * O texto que explica a avaliação para a analista do backoffice (ADR-0035). Só explica: a nota e a
 * decisão já estavam tomadas, pelas regras e pelo modelo de ML, antes de qualquer texto existir.
 *
 * @param modelo o modelo de linguagem que escreveu ({@code gemma3:4b}, por exemplo); {@code null}
 *     no texto fixo
 */
public record Explicacao(String texto, Origem origem, String modelo, Instant explicadaEm) {

    public enum Origem {
        /** Escrito pelo modelo de linguagem local, e aprovado pelo {@link GuardrailDeNumeros}. */
        MODELO_DE_LINGUAGEM,
        /** Montado com os motivos, sem modelo de linguagem: quando ele falha, ou inventa um número. */
        TEXTO_FIXO
    }

    public Explicacao {
        Objects.requireNonNull(texto, "o texto é obrigatório");
        Objects.requireNonNull(origem, "a origem é obrigatória");
        Objects.requireNonNull(explicadaEm, "o instante é obrigatório");
    }

    /** A explicação sem modelo de linguagem: a nota, o que ela quer dizer e os motivos, em uma frase cada. */
    public static Explicacao textoFixo(Avaliacao avaliacao, Instant agora) {
        String desfecho = switch (avaliacao.decisao()) {
            case APROVAR -> "o Pix seguiu sem retenção";
            case SEGURAR -> "o Pix foi retido para uma pessoa analisar";
            case RECUSAR -> "o Pix foi recusado sem análise, pelo risco muito alto";
        };
        String pesou = avaliacao.motivos().isEmpty()
                ? "Nenhum sinal pesou sozinho; a nota veio da combinação deles."
                : "Pesou: " + String.join("; ", avaliacao.motivos().stream().map(Motivo::texto).toList()) + ".";
        return new Explicacao(
                "Nota %d de %d: %s. %s".formatted(avaliacao.nota(), PoliticaDeRisco.NOTA_MAXIMA, desfecho, pesou),
                Origem.TEXTO_FIXO, null, agora);
    }
}
