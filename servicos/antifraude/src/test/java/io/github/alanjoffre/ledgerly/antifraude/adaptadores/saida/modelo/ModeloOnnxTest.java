package io.github.alanjoffre.ledgerly.antifraude.adaptadores.saida.modelo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.alanjoffre.ledgerly.antifraude.dominio.FatosDoPix;
import io.github.alanjoffre.ledgerly.antifraude.dominio.Pontuacao;
import io.github.alanjoffre.ledgerly.antifraude.dominio.Sinal;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Paridade Python × JVM: o modelo que roda aqui dá a mesma probabilidade que o sklearn deu no treino,
 * nas 50 linhas de {@code referencia.csv} (20 fraudes e 30 normais do conjunto de teste). Se alguém
 * treinar de novo e esquecer de copiar um dos arquivos, ou mudar a conta de um sinal num lado só,
 * este teste quebra.
 */
class ModeloOnnxTest {

    private static ModeloOnnx modelo;

    @BeforeAll
    static void abrir() {
        modelo = new ModeloOnnx();
    }

    @AfterAll
    static void fechar() throws Exception {
        modelo.fechar();
    }

    record Linha(float[] sinais, double probabilidade) {}

    static List<Linha> referencia() throws IOException {
        try (InputStream in = ModeloOnnxTest.class.getClassLoader().getResourceAsStream("modelo/referencia.csv")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().skip(1).map(linha -> {
                double[] n = Arrays.stream(linha.split(",")).mapToDouble(Double::parseDouble).toArray();
                float[] x = new float[6];
                for (int i = 0; i < 6; i++) {
                    x[i] = (float) n[i];
                }
                return new Linha(x, n[6]);
            }).toList();
        }
    }

    @ParameterizedTest
    @MethodSource("referencia")
    void daAMesmaProbabilidadeQueOPython(Linha linha) {
        // float32 nos dois lados; a diferença que sobra é de arredondamento.
        assertThat(modelo.probabilidade(linha.sinais())).isCloseTo(linha.probabilidade(), within(1e-5));
    }

    @Test
    void aReferenciaTemFraudeENormal() throws IOException {
        var linhas = referencia();
        assertThat(linhas).hasSize(50);
        assertThat(linhas).anyMatch(l -> l.probabilidade() > 0.5).anyMatch(l -> l.probabilidade() < 0.05);
    }

    @Test
    void osPesosSomadosAoInterceptoDaoAMesmaProbabilidade() {
        // Prova de que a explicação é a do modelo, e não uma conta à parte: a regressão logística é
        // sigmoide(intercepto + soma das partes). O intercepto é o do pesos.json do treino.
        var fatos = new FatosDoPix(new BigDecimal("4800.00"), new BigDecimal("9.6"), 3, true, true, 1);
        Pontuacao p = modelo.pontuar(fatos);
        double soma = -6.044778823852539 + p.pesos().values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(p.probabilidade()).isCloseTo(1 / (1 + Math.exp(-soma)), within(1e-5));
        assertThat(p.pesos()).containsOnlyKeys(Sinal.values());
    }

    @Test
    void pixDeCostumeTemRiscoBaixoEGolpeTemRiscoAlto() {
        var costume = new FatosDoPix(new BigDecimal("85.00"), new BigDecimal("1.1"), 0, false, false, 900);
        var golpe = new FatosDoPix(new BigDecimal("4800.00"), new BigDecimal("9.6"), 4, true, true, 1);
        assertThat(modelo.pontuar(costume).probabilidade()).isLessThan(0.01);
        assertThat(modelo.pontuar(golpe).probabilidade()).isGreaterThan(0.9);
    }

    @Test
    void aVersaoDizQualArquivoDecidiu() {
        assertThat(modelo.versao()).matches("risco-pix@[0-9a-f]{12}");
    }
}
