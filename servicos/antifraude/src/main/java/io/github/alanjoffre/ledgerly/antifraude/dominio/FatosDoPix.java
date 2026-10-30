package io.github.alanjoffre.ledgerly.antifraude.dominio;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Objects;

/**
 * O que se sabe de um Pix na hora de avaliar: o pedido e o histórico do pagador, em números que uma
 * pessoa entende ("12 vezes a média", "conta aberta há 3 dias").
 *
 * <p>{@link #sinais()} transforma os fatos nos seis números que o modelo lê, na mesma ordem e com as
 * mesmas contas do treino ({@code ml/treinar.py}). Mudou aqui, muda lá — e o teste de paridade
 * acusa se só um lado mudou.
 *
 * @param razaoDaMedia o valor dividido pela média dos Pix anteriores do pagador; {@code null} se ele
 *     nunca fez um Pix
 */
public record FatosDoPix(
        BigDecimal valor,
        BigDecimal razaoDaMedia,
        int pixNaUltimaHora,
        boolean recebedorNovo,
        boolean madrugada,
        long idadeDaContaRecebedoraEmDias) {

    /** O horário que define a madrugada: o de Brasília, não o do servidor. */
    public static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

    public FatosDoPix {
        Objects.requireNonNull(valor, "o valor é obrigatório");
        if (valor.signum() <= 0) {
            throw new IllegalArgumentException("o valor do Pix precisa ser positivo");
        }
        if (pixNaUltimaHora < 0 || idadeDaContaRecebedoraEmDias < 0) {
            throw new IllegalArgumentException("contagem e idade não podem ser negativas");
        }
    }

    /** Monta os fatos a partir do pedido e do histórico do pagador. */
    public static FatosDoPix de(PedidoDeAvaliacao pedido, HistoricoDoPagador historico) {
        BigDecimal razao = historico.mediaDosValores() == null
                ? null
                : pedido.valor().divide(historico.mediaDosValores(), MathContext.DECIMAL64);
        int hora = pedido.instante().atZone(BRASILIA).getHour();
        long idade = Math.max(0, Duration.between(pedido.contaRecebedoraAbertaEm(), pedido.instante()).toDays());
        return new FatosDoPix(
                pedido.valor(),
                razao,
                historico.pixNaUltimaHora(),
                !historico.jaPagouEsseRecebedor(),
                hora < 6,
                idade);
    }

    /** Os sinais do modelo, na ordem do treino. */
    public float[] sinais() {
        return new float[] {
            (float) Math.log10(1 + valor.doubleValue()),
            razaoDaMedia == null ? 0f : (float) Math.log10(razaoDaMedia.doubleValue()),
            pixNaUltimaHora,
            recebedorNovo ? 1f : 0f,
            madrugada ? 1f : 0f,
            (float) Math.log10(1 + idadeDaContaRecebedoraEmDias),
        };
    }
}
