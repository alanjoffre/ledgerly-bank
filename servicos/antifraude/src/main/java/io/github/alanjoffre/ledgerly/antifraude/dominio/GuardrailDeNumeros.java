package io.github.alanjoffre.ledgerly.antifraude.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A trava de saída do texto do modelo de linguagem (ADR-0035): <b>todo número do texto tem de
 * existir na avaliação</b> — a nota, os fatos ou os motivos. Um modelo de linguagem escreve bem e
 * inventa com a mesma confiança; para a analista, "15 vezes a média" que na verdade é 1,5 muda a
 * decisão. Número que não veio da avaliação derruba o texto inteiro, e vale o texto fixo.
 *
 * <p>Os números têm de estar no formato brasileiro ({@code 4.800,00}, {@code 15,0}): o ponto só
 * separa milhar, de 3 em 3. Um "1.5" em inglês cai — lido como 15, poderia bater por acaso com
 * outro fato da avaliação.
 *
 * <p>O limite: a trava confere que o número existe na avaliação, não que está no lugar certo.
 * "A conta tem 15 dias" passa se 15 for a razão da média. Pega o número inventado, que é o erro
 * comum de um modelo de linguagem; a troca de um fato por outro, não.
 */
public final class GuardrailDeNumeros {

    /** Mais que isso não é uma explicação curta: o modelo se perdeu. */
    public static final int TAMANHO_MAXIMO = 600;

    private static final Pattern NUMERO = Pattern.compile("\\d+(?:[.,]\\d+)*");
    private static final Pattern BRASILEIRO = Pattern.compile("\\d{1,3}(?:\\.\\d{3})+(?:,\\d+)?|\\d+(?:,\\d+)?");

    private GuardrailDeNumeros() {}

    /** @return o problema do texto, ou vazio se ele pode ir para a analista */
    public static Optional<String> problema(String texto, Avaliacao avaliacao) {
        String limpo = texto == null ? "" : texto.strip();
        if (limpo.isEmpty()) {
            return Optional.of("texto vazio");
        }
        if (limpo.length() > TAMANHO_MAXIMO) {
            return Optional.of("texto com %d caracteres (máximo %d)".formatted(limpo.length(), TAMANHO_MAXIMO));
        }
        List<BigDecimal> permitidos = permitidos(avaliacao);
        for (String numero : numeros(limpo)) {
            if (!BRASILEIRO.matcher(numero).matches()) {
                return Optional.of("o número " + numero + " não está no formato brasileiro");
            }
            BigDecimal valor = ler(numero);
            if (permitidos.stream().noneMatch(p -> p.compareTo(valor) == 0)) {
                return Optional.of("o número " + numero + " não está na avaliação");
            }
        }
        return Optional.empty();
    }

    private static List<BigDecimal> permitidos(Avaliacao avaliacao) {
        FatosDoPix fatos = avaliacao.fatos();
        List<BigDecimal> permitidos = new ArrayList<>(List.of(
                BigDecimal.valueOf(avaliacao.nota()),
                BigDecimal.valueOf(PoliticaDeRisco.NOTA_MAXIMA),
                fatos.valor(),
                BigDecimal.valueOf(fatos.pixNaUltimaHora()),
                BigDecimal.valueOf(fatos.idadeDaContaRecebedoraEmDias())));
        if (fatos.razaoDaMedia() != null) {
            permitidos.add(fatos.razaoDaMedia());
            permitidos.add(ler(PoliticaDeRisco.vezes(fatos.razaoDaMedia())));
            permitidos.add(fatos.razaoDaMedia().setScale(0, RoundingMode.HALF_UP));
        }
        avaliacao.motivos().forEach(m -> numeros(m.texto()).forEach(n -> permitidos.add(ler(n))));
        return permitidos;
    }

    private static List<String> numeros(String texto) {
        List<String> numeros = new ArrayList<>();
        Matcher m = NUMERO.matcher(texto);
        while (m.find()) {
            numeros.add(m.group());
        }
        return numeros;
    }

    /** "4.800,00" → 4800.00; "15,0" → 15.0; "1.500" → 1500. */
    private static BigDecimal ler(String numero) {
        return new BigDecimal(numero.replace(".", "").replace(',', '.'));
    }
}
