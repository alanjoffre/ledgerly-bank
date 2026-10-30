package io.github.alanjoffre.ledgerly.antifraude.dominio;

import java.util.Map;

/**
 * O que o modelo de ML respondeu.
 *
 * @param probabilidade a chance de fraude, de 0 a 1
 * @param pesos quanto cada sinal empurrou a nota para cima (positivo) ou para baixo, na escala do
 *     modelo (log da razão de chances); vazio se o modelo não sabe se explicar
 * @param modelo nome e versão do arquivo do modelo: toda avaliação diz qual modelo decidiu
 */
public record Pontuacao(double probabilidade, Map<Sinal, Double> pesos, String modelo) {

    public Pontuacao {
        if (probabilidade < 0 || probabilidade > 1 || Double.isNaN(probabilidade)) {
            throw new IllegalArgumentException("probabilidade fora de 0 a 1: " + probabilidade);
        }
        pesos = Map.copyOf(pesos);
    }
}
