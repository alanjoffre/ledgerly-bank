package io.github.alanjoffre.ledgerly.antifraude.adaptadores.saida.linguagem;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.alanjoffre.ledgerly.antifraude.dominio.Avaliacao;
import io.github.alanjoffre.ledgerly.antifraude.dominio.Decisao;
import io.github.alanjoffre.ledgerly.antifraude.dominio.FatosDoPix;
import io.github.alanjoffre.ledgerly.antifraude.dominio.GuardrailDeNumeros;
import io.github.alanjoffre.ledgerly.antifraude.dominio.Motivo;
import io.github.alanjoffre.ledgerly.antifraude.dominio.PedidoDeAvaliacao;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("O pedido ao modelo de linguagem")
class RedatorOllamaTest {

    private static final Instant AGORA = Instant.parse("2026-10-29T17:00:00Z");

    private static Avaliacao avaliacao(BigDecimal razao) {
        var pedido = new PedidoDeAvaliacao(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("4800.00"), AGORA, AGORA);
        var fatos = new FatosDoPix(new BigDecimal("4800.00"), razao, 6, true, true, 0);
        return new Avaliacao(pedido, fatos, 930, Decisao.RECUSAR,
                List.of(new Motivo("regra:rajada", "6 Pix do pagador na última hora")), "risco-pix@teste", AGORA);
    }

    @Test
    @DisplayName("leva os fatos no formato brasileiro, e nenhum id de conta")
    void fatos() {
        Avaliacao a = avaliacao(new BigDecimal("1.2500"));
        String pedido = RedatorOllama.pedido(a);
        assertThat(pedido)
                .contains("Decisão do antifraude: recusado (nota 930 de 1000).")
                .contains("- valor: R$ 4.800,00")
                .contains("- em relação à média do pagador: 1,2 vezes a média")
                .contains("- Pix do pagador na última hora: 6")
                .contains("- já pagou esta conta antes: não")
                .contains("- feito de madrugada (horário de Brasília): sim")
                .contains("- idade da conta que recebe, em dias: 0")
                .contains("- 6 Pix do pagador na última hora")
                .doesNotContain(a.pedido().pagadorContaId().toString())
                .doesNotContain(a.pedido().recebedorContaId().toString())
                .doesNotContain(a.pedido().transferenciaId().toString());
    }

    @Test
    @DisplayName("um texto que só copia os números do pedido passa no guardrail")
    void copiarOPedidoPassa() {
        Avaliacao a = avaliacao(new BigDecimal("1.2500"));
        String copia = RedatorOllama.pedido(a).replace("\n", " ").replace("-", "");
        assertThat(GuardrailDeNumeros.problema(copia.substring(0, Math.min(copia.length(), 590)), a)).isEmpty();
    }

    @Test
    @DisplayName("primeiro Pix do pagador: sem média")
    void semMedia() {
        assertThat(RedatorOllama.pedido(avaliacao(null))).contains("- em relação à média do pagador: primeiro Pix do pagador");
    }

    @Test
    @DisplayName("Ollama fora do ar: vazio, sem exceção")
    void foraDoAr() {
        var redator = new RedatorOllama("http://127.0.0.1:9", "gemma3:4b", Duration.ofSeconds(2));
        assertThat(redator.redigir(avaliacao(null))).isEmpty();
        assertThat(redator.modelo()).isEqualTo("gemma3:4b");
    }
}
