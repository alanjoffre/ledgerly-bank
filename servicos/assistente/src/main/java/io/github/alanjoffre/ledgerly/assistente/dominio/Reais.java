package io.github.alanjoffre.ledgerly.assistente.dominio;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dinheiro escrito em português: "R$ 1.234,56". As ferramentas escrevem assim para o modelo, e a
 * trava de saída lê assim da resposta — o mesmo formato dos dois lados, para comparar valor com
 * valor (e não texto com texto).
 */
public final class Reais {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    /**
     * Um valor em dinheiro: com "R$" na frente, ou com centavos depois da vírgula ("1.234,56").
     * Número inteiro solto ("3 lançamentos", "dia 15") não é dinheiro.
     */
    private static final Pattern VALOR = Pattern.compile(
            "R\\$\\s*(\\d{1,3}(?:\\.\\d{3})+(?:,\\d{1,2})?|\\d+(?:,\\d{1,2})?)"
                    + "|(?<![\\d.,])(\\d{1,3}(?:\\.\\d{3})+,\\d{2}|\\d+,\\d{2})(?![\\d,])");

    private Reais() {}

    public static String formatar(BigDecimal valor) {
        return NumberFormat.getCurrencyInstance(PT_BR).format(valor).replace(' ', ' ');
    }

    /** Os valores em dinheiro que aparecem no texto, na ordem. */
    public static List<BigDecimal> valores(String texto) {
        List<BigDecimal> achados = new ArrayList<>();
        Matcher m = VALOR.matcher(texto.replace(' ', ' '));
        while (m.find()) {
            String numero = m.group(1) != null ? m.group(1) : m.group(2);
            achados.add(new BigDecimal(numero.replace(".", "").replace(',', '.')));
        }
        return achados;
    }
}
