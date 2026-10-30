package io.github.alanjoffre.ledgerly.assistente.dominio;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A trava de saída (ADR-0036): a resposta do modelo de linguagem só vai ao cliente se
 * <ul>
 *   <li>todo valor em dinheiro dela saiu de uma ferramenta (o extrato da pessoa) ou de um artigo de
 *       ajuda que foi junto na pergunta — modelo de linguagem inventa número com toda a confiança;</li>
 *   <li>não traz chave Pix inteira (e-mail, CPF, telefone, chave aleatória): a resposta pode ir
 *       parar num print de tela.</li>
 * </ul>
 *
 * <p>Confere que o valor existe, não que está no lugar certo: "seu saldo é R$ 80,00" passa se
 * R$ 80,00 for o valor de um lançamento. É o mesmo limite da trava da explicação do antifraude
 * (ADR-0035), e as evals medem o resto.
 */
public final class GuardrailDeSaida {

    public static final int TAMANHO_MAXIMO = 1200;

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    private static final Pattern CHAVE_ALEATORIA = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    /** CPF, CNPJ ou telefone: 11 dígitos ou mais, com ou sem pontuação no meio. */
    private static final Pattern DOCUMENTO_OU_TELEFONE = Pattern.compile("(?<![\\d,])\\+?(?:\\d[\\s./()-]{0,2}){11,}");

    private GuardrailDeSaida() {}

    /** O que impede a resposta de ir ao cliente; vazio quando ela pode ir. */
    public static Optional<String> problema(String resposta, List<Consulta> consultas, List<Trecho> trechos) {
        if (resposta == null || resposta.isBlank()) {
            return Optional.of("resposta vazia");
        }
        if (resposta.length() > TAMANHO_MAXIMO) {
            return Optional.of("resposta com %d caracteres (máximo %d)".formatted(resposta.length(), TAMANHO_MAXIMO));
        }
        if (EMAIL.matcher(resposta).find() || CHAVE_ALEATORIA.matcher(resposta).find()
                || DOCUMENTO_OU_TELEFONE.matcher(resposta).find()) {
            return Optional.of("a resposta traz uma chave Pix, um documento ou um telefone");
        }
        List<BigDecimal> conhecidos = Stream.concat(
                        consultas.stream().map(Consulta::resultado),
                        trechos.stream().map(Trecho::texto))
                .flatMap(t -> Reais.valores(t).stream())
                .toList();
        return Reais.valores(resposta).stream()
                .filter(v -> conhecidos.stream().noneMatch(c -> c.compareTo(v) == 0))
                .findFirst()
                .map(v -> "o valor %s não veio do extrato nem da ajuda".formatted(Reais.formatar(v)));
    }
}
