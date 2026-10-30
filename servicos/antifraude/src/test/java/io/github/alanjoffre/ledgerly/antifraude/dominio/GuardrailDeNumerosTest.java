package io.github.alanjoffre.ledgerly.antifraude.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Guardrail de números da explicação (ADR-0035)")
class GuardrailDeNumerosTest {

    private static final Instant AGORA = Instant.parse("2026-10-29T17:00:00Z");

    /** R$ 4.800,00, 15 vezes a média, 1 Pix na hora, conta de 3 dias, nota 770. */
    static Avaliacao retida() {
        var pedido = new PedidoDeAvaliacao(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("4800.00"), AGORA, AGORA.minusSeconds(3 * 86_400));
        var fatos = new FatosDoPix(new BigDecimal("4800.00"), new BigDecimal("15.0000"), 1, true, false, 3);
        return new Avaliacao(pedido, fatos, 770, Decisao.SEGURAR, List.of(
                new Motivo("modelo:fora-do-costume", "15,0 vezes a média dos Pix anteriores do pagador"),
                new Motivo("modelo:recebedor-novo", "primeiro Pix do pagador para esta conta")),
                "risco-pix@teste", AGORA);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "O Pix de R$ 4.800,00 é 15 vezes a média do pagador e vai para uma conta que ele nunca pagou.",
        "Nota 770 de 1000: o valor, 15,0 vezes a média, chamou a atenção; a conta que recebe tem 3 dias.",
        "Valor de R$ 4800,00 para um recebedor novo, com 1 Pix na última hora.",
        "O valor fugiu do costume do pagador e o recebedor é novo para ele.",
    })
    @DisplayName("passa: todo número veio da avaliação (ou não há número)")
    void passa(String texto) {
        assertThat(GuardrailDeNumeros.problema(texto, retida())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "O Pix de R$ 48.000,00 é muito acima da média.",
        "O valor é 1,5 vez a média do pagador.",
        "Foi feito às 4h da manhã, com nota 770.",
        "Nota 800 de 1000.",
        "A conta que recebe foi aberta há 2 dias.",
    })
    @DisplayName("cai: número que não está na avaliação")
    void cai(String texto) {
        assertThat(GuardrailDeNumeros.problema(texto, retida())).get().asString().contains("não está na avaliação");
    }

    @ParameterizedTest
    @ValueSource(strings = {"O valor é 1.5 vez a média do pagador.", "R$ 4,800.00", "R$ 48.00,00"})
    @DisplayName("cai: número fora do formato brasileiro (mesmo que, lido de outro jeito, batesse)")
    void formato(String texto) {
        assertThat(GuardrailDeNumeros.problema(texto, retida())).get().asString().contains("formato brasileiro");
    }

    @Test
    @DisplayName("cai: texto vazio ou longo demais")
    void tamanho() {
        assertThat(GuardrailDeNumeros.problema("  ", retida())).contains("texto vazio");
        assertThat(GuardrailDeNumeros.problema(null, retida())).contains("texto vazio");
        assertThat(GuardrailDeNumeros.problema("a".repeat(GuardrailDeNumeros.TAMANHO_MAXIMO + 1), retida()))
                .get().asString().startsWith("texto com 601 caracteres");
    }

    @Test
    @DisplayName("o número de um motivo vale: \"menos de 1 dia\", \"R$ 1.200,00\"")
    void numerosDosMotivos() {
        var pedido = new PedidoDeAvaliacao(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("1200.00"), AGORA, AGORA);
        var fatos = new FatosDoPix(new BigDecimal("1200.00"), null, 0, true, false, 0);
        var a = new Avaliacao(pedido, fatos, 500, Decisao.SEGURAR, List.of(new Motivo("regra:conta-nova-valor-alto",
                "R$ 1.200,00 para uma conta aberta há menos de 1 dia, que nunca recebeu deste pagador")), "m", AGORA);
        assertThat(GuardrailDeNumeros.problema(
                "R$ 1.200,00 vão para uma conta aberta há menos de 1 dia, primeiro Pix do pagador.", a)).isEmpty();
        assertThat(GuardrailDeNumeros.problema("A conta tem 7 dias.", a)).isPresent();
    }

    @Test
    @DisplayName("texto fixo: nota, desfecho e motivos, sem modelo de linguagem")
    void textoFixo() {
        Explicacao e = Explicacao.textoFixo(retida(), AGORA);
        assertThat(e.texto()).isEqualTo("Nota 770 de 1000: o Pix foi retido para uma pessoa analisar. "
                + "Pesou: 15,0 vezes a média dos Pix anteriores do pagador; primeiro Pix do pagador para esta conta.");
        assertThat(e.origem()).isEqualTo(Explicacao.Origem.TEXTO_FIXO);
        assertThat(e.modelo()).isNull();
        // O texto fixo passa no próprio guardrail: ele também só usa números da avaliação.
        assertThat(GuardrailDeNumeros.problema(e.texto(), retida())).isEmpty();
    }

    @Test
    @DisplayName("tamanho: até o máximo passa; um caractere a mais cai")
    void tamanhoNoLimite() {
        assertThat(GuardrailDeNumeros.problema("x".repeat(GuardrailDeNumeros.TAMANHO_MAXIMO), retida())).isEmpty();
        assertThat(GuardrailDeNumeros.problema("x".repeat(GuardrailDeNumeros.TAMANHO_MAXIMO + 1), retida())).isPresent();
    }
}
