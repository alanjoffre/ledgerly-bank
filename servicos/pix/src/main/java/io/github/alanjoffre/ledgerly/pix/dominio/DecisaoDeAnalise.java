package io.github.alanjoffre.ledgerly.pix.dominio;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * O registro de auditoria de uma decisão do backoffice sobre um Pix (ADR-0030, ADR-0033): quem
 * decidiu, o quê, para qual conta, por quê e quando. Só se acrescenta; nunca muda nem sai — como o
 * razão.
 *
 * @param analistaId o {@code sub} do token do analista (o id dele no Keycloak)
 * @param contaDestinoId a conta da devolução; {@code null} em liberar e recusar
 */
public record DecisaoDeAnalise(
        UUID id,
        UUID transferenciaId,
        String analistaId,
        String analistaNome,
        Decisao decisao,
        UUID contaDestinoId,
        String justificativa,
        Instant decididaEm) {

    public static final int TAMANHO_MINIMO_JUSTIFICATIVA = 10;
    public static final int TAMANHO_MAXIMO_JUSTIFICATIVA = 500;

    public enum Decisao {
        /** Pix em análise: devolver para outra conta do titular (ADR-0030). */
        DEVOLVER,
        /** Pix retido pelo antifraude: pode sair (ADR-0033). */
        LIBERAR,
        /** Pix retido pelo antifraude: não sai. */
        RECUSAR
    }

    public DecisaoDeAnalise {
        Objects.requireNonNull(id, "id é obrigatório");
        Objects.requireNonNull(transferenciaId, "a transferência é obrigatória");
        Objects.requireNonNull(analistaId, "o analista é obrigatório");
        Objects.requireNonNull(decisao, "a decisão é obrigatória");
        Objects.requireNonNull(decididaEm, "o momento é obrigatório");
        if ((decisao == Decisao.DEVOLVER) != (contaDestinoId != null)) {
            throw new TransferenciaInvalidaException("Só a devolução tem conta de destino");
        }
        String texto = justificativa == null ? "" : justificativa.strip();
        if (texto.length() < TAMANHO_MINIMO_JUSTIFICATIVA || texto.length() > TAMANHO_MAXIMO_JUSTIFICATIVA) {
            throw new TransferenciaInvalidaException("A justificativa deve ter de %d a %d caracteres"
                    .formatted(TAMANHO_MINIMO_JUSTIFICATIVA, TAMANHO_MAXIMO_JUSTIFICATIVA));
        }
        justificativa = texto;
    }
}
