package io.github.alanjoffre.ledgerly.antifraude.aplicacao.porta;

import io.github.alanjoffre.ledgerly.antifraude.dominio.FatosDoPix;
import io.github.alanjoffre.ledgerly.antifraude.dominio.Pontuacao;

/** O modelo de ML que dá a chance de um Pix ser fraude (hoje, o ONNX treinado em {@code ml/}). */
public interface ModeloDeRisco {

    Pontuacao pontuar(FatosDoPix fatos);
}
