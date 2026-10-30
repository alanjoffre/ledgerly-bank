package io.github.alanjoffre.ledgerly.antifraude.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PoliticaDeRiscoTest {

    private static final Instant AGORA = Instant.parse("2026-10-29T17:00:00Z");
    private final PoliticaDeRisco politica = new PoliticaDeRisco();

    private static PedidoDeAvaliacao pedido(String valor) {
        return new PedidoDeAvaliacao(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(valor), AGORA, AGORA);
    }

    private static FatosDoPix fatos(String valor, int naHora, boolean recebedorNovo, long idade) {
        return new FatosDoPix(new BigDecimal(valor), new BigDecimal("1.0"), naHora, recebedorNovo, false, idade);
    }

    private static Pontuacao modelo(double probabilidade, Map<Sinal, Double> pesos) {
        return new Pontuacao(probabilidade, pesos, "risco-pix@teste");
    }

    private Avaliacao avaliar(FatosDoPix f, Pontuacao p) {
        return politica.avaliar(pedido(f.valor().toPlainString()), f, p, AGORA);
    }

    @Test
    void aNotaEAProbabilidadeEmMil() {
        var a = avaliar(fatos("50.00", 0, false, 900), modelo(0.0123, Map.of()));
        assertThat(a.nota()).isEqualTo(12);
        assertThat(a.decisao()).isEqualTo(Decisao.APROVAR);
        assertThat(a.motivos()).isEmpty();
        assertThat(a.modelo()).isEqualTo("risco-pix@teste");
    }

    @Test
    void osLimitesDeSegurarEDeRecusar() {
        assertThat(avaliar(fatos("50.00", 0, false, 900), modelo(0.199, Map.of())).decisao()).isEqualTo(Decisao.APROVAR);
        assertThat(avaliar(fatos("50.00", 0, false, 900), modelo(0.200, Map.of())).decisao()).isEqualTo(Decisao.SEGURAR);
        assertThat(avaliar(fatos("50.00", 0, false, 900), modelo(0.899, Map.of())).decisao()).isEqualTo(Decisao.SEGURAR);
        assertThat(avaliar(fatos("50.00", 0, false, 900), modelo(0.900, Map.of())).decisao()).isEqualTo(Decisao.RECUSAR);
    }

    @Test
    void rajadaSeguraMesmoComOModeloTranquilo() {
        var a = avaliar(fatos("50.00", 5, false, 900), modelo(0.01, Map.of()));
        assertThat(a.nota()).isEqualTo(PoliticaDeRisco.SEGURAR_A_PARTIR_DE);
        assertThat(a.decisao()).isEqualTo(Decisao.SEGURAR);
        assertThat(a.motivos()).extracting(Motivo::codigo).containsExactly("regra:rajada");
        assertThat(a.motivos().getFirst().texto()).isEqualTo("5 Pix do pagador na última hora");
    }

    @Test
    void quatroPixNaHoraNaoERajada() {
        assertThat(avaliar(fatos("50.00", 4, false, 900), modelo(0.01, Map.of())).decisao()).isEqualTo(Decisao.APROVAR);
    }

    @Test
    void contaLaranjaSegura() {
        var a = avaliar(fatos("1000.00", 0, true, 1), modelo(0.05, Map.of()));
        assertThat(a.nota()).isEqualTo(500);
        assertThat(a.decisao()).isEqualTo(Decisao.SEGURAR);
        assertThat(a.motivos().getFirst().texto())
                .isEqualTo("R$ 1.000,00 para uma conta aberta há 1 dia, que nunca recebeu deste pagador");
    }

    @Test
    void contaLaranjaPrecisaDasTresCoisas() {
        assertThat(avaliar(fatos("999.99", 0, true, 1), modelo(0.05, Map.of())).decisao()).isEqualTo(Decisao.APROVAR);
        assertThat(avaliar(fatos("1000.00", 0, false, 1), modelo(0.05, Map.of())).decisao()).isEqualTo(Decisao.APROVAR);
        assertThat(avaliar(fatos("1000.00", 0, true, 2), modelo(0.05, Map.of())).decisao()).isEqualTo(Decisao.APROVAR);
    }

    @Test
    void regraNenhumaRecusaSozinha() {
        var a = avaliar(fatos("5000.00", 9, true, 0), modelo(0.10, Map.of()));
        assertThat(a.decisao()).isEqualTo(Decisao.SEGURAR);
    }

    @Test
    void regraSoSobeANotaNuncaBaixa() {
        var a = avaliar(fatos("5000.00", 9, true, 0), modelo(0.95, Map.of()));
        assertThat(a.nota()).isEqualTo(950);
        assertThat(a.decisao()).isEqualTo(Decisao.RECUSAR);
    }

    @Test
    void osMotivosDoModeloSaoOsTresSinaisQueMaisPesaram() {
        Map<Sinal, Double> pesos = new EnumMap<>(Sinal.class);
        pesos.put(Sinal.VALOR, 0.9);
        pesos.put(Sinal.FORA_DO_COSTUME, 1.4);
        pesos.put(Sinal.RECEBEDOR_NOVO, 0.5);
        pesos.put(Sinal.MADRUGADA, 0.3);
        pesos.put(Sinal.PIX_NA_ULTIMA_HORA, 0.2); // abaixo do mínimo: não vira motivo
        pesos.put(Sinal.CONTA_RECEBEDORA_NOVA, -0.8); // puxou para baixo: não é motivo de risco
        var f = new FatosDoPix(new BigDecimal("4800.00"), new BigDecimal("9.6"), 1, true, true, 400);
        var a = avaliar(f, modelo(0.4, pesos));
        assertThat(a.motivos()).extracting(Motivo::codigo)
                .containsExactly("modelo:fora-do-costume", "modelo:valor", "modelo:recebedor-novo");
        assertThat(a.motivos()).extracting(Motivo::texto).containsExactly(
                "9,6 vezes a média dos Pix anteriores do pagador",
                "valor de R$ 4.800,00",
                "primeiro Pix do pagador para esta conta");
    }

    @Test
    void motivoDoModeloIgualAoDaRegraNaoRepete() {
        var a = avaliar(fatos("50.00", 6, false, 900), modelo(0.3, Map.of(Sinal.PIX_NA_ULTIMA_HORA, 1.2)));
        assertThat(a.motivos()).extracting(Motivo::codigo).containsExactly("regra:rajada");
    }
}
