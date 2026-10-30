package io.github.alanjoffre.ledgerly.antifraude.aplicacao.porta;

import io.github.alanjoffre.ledgerly.antifraude.dominio.Avaliacao;
import java.util.Optional;

/** Quem escreve a explicação de uma avaliação: o modelo de linguagem local (ADR-0034). */
public interface Redator {

    /**
     * Um texto curto, em português, explicando a avaliação para a analista. Nunca lança exceção.
     *
     * @return vazio se o modelo de linguagem não respondeu (fora do ar, lento); a próxima leitura
     *     tenta de novo
     */
    Optional<String> redigir(Avaliacao avaliacao);

    /** O modelo que escreve (fica gravado com o texto). */
    String modelo();
}
