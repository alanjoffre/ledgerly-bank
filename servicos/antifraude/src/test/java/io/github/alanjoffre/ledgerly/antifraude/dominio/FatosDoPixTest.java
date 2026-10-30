package io.github.alanjoffre.ledgerly.antifraude.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FatosDoPixTest {

    // 29/10/2026 14:00 em Brasília (UTC−3, sem horário de verão).
    private static final Instant TARDE = Instant.parse("2026-10-29T17:00:00Z");

    private static PedidoDeAvaliacao pedido(String valor, Instant instante, Duration idadeDaConta) {
        return new PedidoDeAvaliacao(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(valor), instante,
                instante.minus(idadeDaConta));
    }

    @Test
    void comparaOValorComAMediaDoPagador() {
        var fatos = FatosDoPix.de(pedido("600.00", TARDE, Duration.ofDays(30)),
                new HistoricoDoPagador(new BigDecimal("50.00"), 1, true));
        assertThat(fatos.razaoDaMedia()).isEqualByComparingTo("12");
        assertThat(fatos.recebedorNovo()).isFalse();
        assertThat(fatos.pixNaUltimaHora()).isEqualTo(1);
        assertThat(fatos.idadeDaContaRecebedoraEmDias()).isEqualTo(30);
    }

    @Test
    void semHistoricoNaoHaMediaEORecebedorENovo() {
        var fatos = FatosDoPix.de(pedido("600.00", TARDE, Duration.ofDays(30)), HistoricoDoPagador.VAZIO);
        assertThat(fatos.razaoDaMedia()).isNull();
        assertThat(fatos.recebedorNovo()).isTrue();
        assertThat(fatos.sinais()[1]).isZero();
    }

    @Test
    void madrugadaEPeloHorarioDeBrasiliaNaoPeloDoServidor() {
        // 08:30 em UTC = 05:30 em Brasília: madrugada. 09:00 UTC = 06:00: já não é.
        var as0530 = FatosDoPix.de(pedido("10.00", Instant.parse("2026-10-29T08:30:00Z"), Duration.ofDays(9)),
                HistoricoDoPagador.VAZIO);
        var as0600 = FatosDoPix.de(pedido("10.00", Instant.parse("2026-10-29T09:00:00Z"), Duration.ofDays(9)),
                HistoricoDoPagador.VAZIO);
        assertThat(as0530.madrugada()).isTrue();
        assertThat(as0600.madrugada()).isFalse();
    }

    @Test
    void contaAbertaHaMenosDeUmDiaTemIdadeZero() {
        var fatos = FatosDoPix.de(pedido("10.00", TARDE, Duration.ofHours(5)), HistoricoDoPagador.VAZIO);
        assertThat(fatos.idadeDaContaRecebedoraEmDias()).isZero();
    }

    @Test
    void osSinaisSaoAsMesmasContasDoTreino() {
        var fatos = new FatosDoPix(new BigDecimal("999"), new BigDecimal("100"), 3, true, false, 9);
        // log10(1 + 999) = 3; log10(100) = 2; log10(1 + 9) = 1.
        assertThat(fatos.sinais()).containsExactly(new float[] {3f, 2f, 3f, 1f, 0f, 1f}, within(1e-6f));
    }

    @Test
    void valorPrecisaSerPositivo() {
        assertThatThrownBy(() -> new FatosDoPix(BigDecimal.ZERO, null, 0, true, false, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
