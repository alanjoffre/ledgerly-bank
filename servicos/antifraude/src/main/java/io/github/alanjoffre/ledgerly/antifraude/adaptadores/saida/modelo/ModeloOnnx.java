package io.github.alanjoffre.ledgerly.antifraude.adaptadores.saida.modelo;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.alanjoffre.ledgerly.antifraude.aplicacao.porta.ModeloDeRisco;
import io.github.alanjoffre.ledgerly.antifraude.dominio.FatosDoPix;
import io.github.alanjoffre.ledgerly.antifraude.dominio.Pontuacao;
import io.github.alanjoffre.ledgerly.antifraude.dominio.Sinal;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Executa na JVM o modelo treinado em Python ({@code ml/treinar.py}), pelo ONNX Runtime (ADR-0032).
 *
 * <p>O arquivo vem dentro do jar ({@code modelo/risco-pix.onnx}) e a sessão abre uma vez, na
 * subida: pontuar um Pix é só montar um vetor de seis números e rodar.
 *
 * <p><b>Os pesos de cada sinal</b> saem de {@code modelo/pesos.json}, que o treino grava junto: a
 * regressão logística é uma soma, e a parte de cada sinal nela é {@code peso × (x − média) / escala}.
 * O ONNX dá a probabilidade; os pesos dão o "porquê".
 */
@ApplicationScoped
public class ModeloOnnx implements ModeloDeRisco {

    static final String ARQUIVO = "modelo/risco-pix.onnx";
    static final String PESOS = "modelo/pesos.json";

    private final OrtEnvironment ambiente = OrtEnvironment.getEnvironment();
    private final OrtSession sessao;
    private final String entrada;
    private final Explicacao explicacao;
    private final String versao;

    public ModeloOnnx() {
        byte[] modelo = ler(ARQUIVO);
        try {
            this.sessao = ambiente.createSession(modelo, new OrtSession.SessionOptions());
        } catch (OrtException e) {
            throw new IllegalStateException("não foi possível abrir o modelo " + ARQUIVO, e);
        }
        this.entrada = sessao.getInputNames().iterator().next();
        this.explicacao = Explicacao.de(ler(PESOS));
        this.versao = "risco-pix@" + HexFormat.of().formatHex(sha256(modelo), 0, 6);
    }

    @Override
    public Pontuacao pontuar(FatosDoPix fatos) {
        float[] x = fatos.sinais();
        return new Pontuacao(probabilidade(x), explicacao.pesos(x), versao);
    }

    /** A probabilidade de fraude que o ONNX calcula para um vetor de sinais (o teste de paridade usa direto). */
    double probabilidade(float[] x) {
        try (OnnxTensor tensor = OnnxTensor.createTensor(ambiente, new float[][] {x});
                OrtSession.Result resultado = sessao.run(Map.of(entrada, tensor))) {
            // Saídas do sklearn com zipmap desligado: [0] o rótulo, [1] as probabilidades [normal, fraude].
            float[][] probabilidades = (float[][]) resultado.get(1).getValue();
            return probabilidades[0][1];
        } catch (OrtException e) {
            throw new IllegalStateException("o modelo falhou ao pontuar", e);
        }
    }

    String versao() {
        return versao;
    }

    @PreDestroy
    void fechar() throws OrtException {
        sessao.close();
    }

    private static byte[] ler(String recurso) {
        try (InputStream in = ModeloOnnx.class.getClassLoader().getResourceAsStream(recurso)) {
            if (in == null) {
                throw new IllegalStateException("recurso não encontrado: " + recurso);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** O {@code pesos.json} do treino: média, escala e peso de cada sinal, na ordem de {@link Sinal}. */
    record Explicacao(List<Double> media, List<Double> escala, List<Double> pesos) {

        static Explicacao de(byte[] json) {
            try {
                Explicacao lida = new ObjectMapper()
                        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                        .readValue(json, Explicacao.class);
                int n = Sinal.values().length;
                if (lida.media().size() != n || lida.escala().size() != n || lida.pesos().size() != n) {
                    throw new IllegalStateException("pesos.json não tem os " + n + " sinais do modelo");
                }
                return lida;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        Map<Sinal, Double> pesos(float[] x) {
            Map<Sinal, Double> parte = new EnumMap<>(Sinal.class);
            for (Sinal s : Sinal.values()) {
                int i = s.ordinal();
                parte.put(s, pesos.get(i) * (x[i] - media.get(i)) / escala.get(i));
            }
            return parte;
        }
    }
}
