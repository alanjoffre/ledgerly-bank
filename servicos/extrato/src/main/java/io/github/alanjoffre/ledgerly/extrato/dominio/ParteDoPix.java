package io.github.alanjoffre.ledgerly.extrato.dominio;

import java.util.Objects;
import java.util.UUID;

/**
 * O pedaço de uma linha do extrato que vem do Pix (eventos {@code PixConcluido} e
 * {@code PixEstornado}): quem é a outra pessoa. É opcional — sem ele a linha aparece com um texto
 * genérico ("Pix enviado"), e ganha o nome quando ele chegar.
 *
 * @param pixId       o Pix de onde veio
 * @param papel       enviado, recebido ou estorno, do ponto de vista do dono da conta
 * @param situacao    como o Pix terminou
 * @param contraparte o nome da outra pessoa
 * @param tipoChave   tipo da chave do recebedor (CPF, EMAIL...), quando o dono da conta pagou
 * @param chave       a chave do recebedor, já mascarada pelo Pix
 * @param motivo      por que o Pix foi estornado, se foi
 */
public record ParteDoPix(
        UUID pixId,
        PapelNoPix papel,
        SituacaoPix situacao,
        String contraparte,
        String tipoChave,
        String chave,
        String motivo) {

    public ParteDoPix {
        Objects.requireNonNull(pixId, "pixId é obrigatório");
        Objects.requireNonNull(papel, "papel é obrigatório");
        Objects.requireNonNull(situacao, "situação é obrigatória");
        Objects.requireNonNull(contraparte, "contraparte é obrigatória");
    }
}
