package io.github.alanjoffre.ledgerly.antifraude.dominio;

import java.math.BigDecimal;

/**
 * O costume do pagador, tirado das avaliações anteriores dele (o antifraude não lê o banco de
 * ninguém: aprende com os Pix que avaliou).
 *
 * @param mediaDosValores média dos últimos Pix; {@code null} se ele nunca fez um
 * @param pixNaUltimaHora quantos Pix ele tentou na hora anterior (os recusados contam: rajada de
 *     tentativas também é sinal)
 */
public record HistoricoDoPagador(BigDecimal mediaDosValores, int pixNaUltimaHora, boolean jaPagouEsseRecebedor) {

    public static final HistoricoDoPagador VAZIO = new HistoricoDoPagador(null, 0, false);
}
