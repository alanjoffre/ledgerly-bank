package io.github.alanjoffre.ledgerly.nucleo;

import java.io.Serial;
import java.util.Currency;

/**
 * Tentativa de somar, subtrair ou comparar quantias de moedas diferentes
 * (por exemplo, real com dólar). Converter moeda exige uma cotação, então
 * nunca acontece implicitamente.
 */
public final class MoedasDiferentesException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 1L;

    public MoedasDiferentesException(Currency esperada, Currency recebida) {
        super("Moedas diferentes: esperado %s, recebido %s"
                .formatted(esperada.getCurrencyCode(), recebida.getCurrencyCode()));
    }
}
