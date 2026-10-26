package io.github.alanjoffre.ledgerly.nucleo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Uma quantia de dinheiro numa moeda — o tipo mais importante de um banco.
 *
 * <p><b>Padrão:</b> <i>Value Object</i> (DDD). É imutável (toda operação devolve um
 * novo {@code Dinheiro}) e dois objetos com o mesmo valor e a mesma moeda são iguais.
 *
 * <p><b>Por que não usar {@code double}:</b> {@code double} guarda números em base 2 e
 * não consegue representar 0,10 exatamente — {@code 0.1 + 0.2} dá {@code 0.30000000000000004}.
 * Num banco, isso vira centavo sumindo. Por isso o valor é um {@link BigDecimal}
 * (ver {@code docs/adr/0007-dinheiro-com-bigdecimal.md}).
 *
 * <p><b>Regras garantidas aqui:</b>
 * <ul>
 *   <li>o valor tem sempre as casas decimais da moeda (BRL = 2); um valor com mais
 *       casas é <b>recusado</b>, nunca arredondado em silêncio;</li>
 *   <li>não se soma nem compara real com dólar ({@link MoedasDiferentesException});</li>
 *   <li>o rateio ({@link #ratear(int)}) nunca perde nem cria centavo.</li>
 * </ul>
 *
 * @param valor a quantia, já na escala da moeda
 * @param moeda a moeda (ISO 4217, por exemplo BRL)
 */
public record Dinheiro(BigDecimal valor, Currency moeda) implements Comparable<Dinheiro> {

    /** Real brasileiro, a moeda padrão do Ledgerly Bank. */
    public static final Currency REAL = Currency.getInstance("BRL");

    /**
     * Arredondamento bancário (<i>banker's rounding</i>, ou "meio para o par"):
     * 2,345 → 2,34 e 2,355 → 2,36. Em muitas operações, os arredondamentos
     * para cima e para baixo se compensam, em vez de puxar tudo para cima.
     */
    public static final RoundingMode ARREDONDAMENTO = RoundingMode.HALF_EVEN;

    /**
     * Construtor compacto do {@code record}: roda antes de o objeto existir e
     * é onde as regras de validade são garantidas.
     */
    public Dinheiro {
        Objects.requireNonNull(valor, "valor é obrigatório");
        Objects.requireNonNull(moeda, "moeda é obrigatória");
        int casas = moeda.getDefaultFractionDigits();
        if (valor.stripTrailingZeros().scale() > casas) {
            throw new IllegalArgumentException(
                    "O valor %s tem mais de %d casas decimais, o máximo para %s"
                            .formatted(valor.toPlainString(), casas, moeda.getCurrencyCode()));
        }
        // Normaliza a escala: 10, 10.0 e 10.00 viram todos 10.00 — e passam a ser iguais.
        valor = valor.setScale(casas, RoundingMode.UNNECESSARY);
    }

    /** Cria uma quantia em reais a partir de texto, por exemplo {@code reais("10.50")}. */
    public static Dinheiro reais(String valor) {
        return new Dinheiro(new BigDecimal(valor), REAL);
    }

    /** Cria uma quantia em qualquer moeda. */
    public static Dinheiro de(BigDecimal valor, Currency moeda) {
        return new Dinheiro(valor, moeda);
    }

    /** Zero na moeda informada. */
    public static Dinheiro zero(Currency moeda) {
        return new Dinheiro(BigDecimal.ZERO, moeda);
    }

    public Dinheiro somar(Dinheiro outro) {
        exigirMesmaMoeda(outro);
        return new Dinheiro(valor.add(outro.valor), moeda);
    }

    public Dinheiro subtrair(Dinheiro outro) {
        exigirMesmaMoeda(outro);
        return new Dinheiro(valor.subtract(outro.valor), moeda);
    }

    /**
     * Multiplica por um fator (juros, tarifa percentual, câmbio). Aqui o
     * resultado pode ter mais casas que a moeda, então é arredondado com
     * {@link #ARREDONDAMENTO} — o único ponto onde há arredondamento.
     */
    public Dinheiro multiplicar(BigDecimal fator) {
        Objects.requireNonNull(fator, "fator é obrigatório");
        BigDecimal resultado = valor.multiply(fator)
                .setScale(moeda.getDefaultFractionDigits(), ARREDONDAMENTO);
        return new Dinheiro(resultado, moeda);
    }

    public Dinheiro negar() {
        return new Dinheiro(valor.negate(), moeda);
    }

    /**
     * Divide a quantia em {@code partes} pedaços sem perder centavo.
     *
     * <p>Exemplo: R$ 100,00 em 3 partes dá [33,34; 33,33; 33,33] — a soma volta a ser
     * exatamente 100,00. A divisão comum daria 33,33 × 3 = 99,99 e um centavo sumiria.
     *
     * <p>Como funciona: trabalha em centavos (inteiros), dá a cada parte o quociente
     * e distribui o resto, um centavo por vez, a partir da primeira parte.
     * (Algoritmo de alocação de Martin Fowler, <i>Patterns of Enterprise Application Architecture</i>.)
     */
    public List<Dinheiro> ratear(int partes) {
        if (partes <= 0) {
            throw new IllegalArgumentException("O número de partes deve ser positivo: " + partes);
        }
        int casas = moeda.getDefaultFractionDigits();
        BigDecimal[] quocienteEResto = valor.movePointRight(casas)
                .divideAndRemainder(BigDecimal.valueOf(partes));
        BigDecimal base = quocienteEResto[0];
        // Para valor negativo o resto é negativo: cada ajuste é -1 centavo.
        int restante = quocienteEResto[1].intValueExact();
        BigDecimal ajuste = BigDecimal.valueOf(Integer.signum(restante));

        List<Dinheiro> resultado = new ArrayList<>(partes);
        for (int i = 0; i < partes; i++) {
            BigDecimal centavos = i < Math.abs(restante) ? base.add(ajuste) : base;
            resultado.add(new Dinheiro(centavos.movePointLeft(casas), moeda));
        }
        return List.copyOf(resultado);
    }

    public boolean ehPositivo() {
        return valor.signum() > 0;
    }

    public boolean ehNegativo() {
        return valor.signum() < 0;
    }

    public boolean ehZero() {
        return valor.signum() == 0;
    }

    public boolean ehMaiorQue(Dinheiro outro) {
        return compareTo(outro) > 0;
    }

    public boolean ehMenorQue(Dinheiro outro) {
        return compareTo(outro) < 0;
    }

    @Override
    public int compareTo(Dinheiro outro) {
        exigirMesmaMoeda(outro);
        return valor.compareTo(outro.valor);
    }

    /** Formata para exibir ao cliente, por exemplo {@code R$ 1.234,56} em pt-BR. */
    public String formatar(Locale local) {
        NumberFormat formato = NumberFormat.getCurrencyInstance(local);
        formato.setCurrency(moeda);
        return formato.format(valor);
    }

    /** Formato técnico, para log: {@code BRL 10.50}. */
    @Override
    public String toString() {
        return moeda.getCurrencyCode() + " " + valor.toPlainString();
    }

    private void exigirMesmaMoeda(Dinheiro outro) {
        Objects.requireNonNull(outro, "a outra quantia é obrigatória");
        if (!moeda.equals(outro.moeda)) {
            throw new MoedasDiferentesException(moeda, outro.moeda);
        }
    }
}
