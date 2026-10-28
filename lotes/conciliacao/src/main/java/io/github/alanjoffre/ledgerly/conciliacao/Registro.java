package io.github.alanjoffre.ledgerly.conciliacao;

import java.util.Objects;
import java.util.UUID;

/**
 * Um lançamento de Pix, do jeito que cada lado o vê: o Pix pelo que guardou na transferência
 * (os ids dos lançamentos de envio, recebimento e estorno), o razão pelo que escriturou.
 *
 * <p>O valor vai em centavos, num {@code long}: conciliar é só comparar, e comparar dois
 * {@code long} é muito mais barato que dois {@code BigDecimal} — medido no JMH (ADR-0023). Os
 * serviços continuam com {@code BigDecimal} (ADR-0007), onde há conta de verdade.
 *
 * @param id       o id do lançamento no razão
 * @param tipo     envio, recebimento ou estorno
 * @param centavos o valor, em centavos
 */
public record Registro(UUID id, Tipo tipo, long centavos) {

    /** Os três lançamentos que um Pix produz no razão. */
    public enum Tipo {
        PIX_ENVIO,
        PIX_RECEBIMENTO,
        PIX_ESTORNO
    }

    public Registro {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tipo, "tipo");
        if (centavos <= 0) {
            throw new IllegalArgumentException("Valor deve ser positivo: " + centavos);
        }
    }
}
