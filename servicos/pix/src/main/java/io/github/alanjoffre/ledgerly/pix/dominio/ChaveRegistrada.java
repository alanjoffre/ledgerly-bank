package io.github.alanjoffre.ledgerly.pix.dominio;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Uma chave Pix registrada: aponta para a conta de um cliente do Ledgerly.
 *
 * @param chave        a chave
 * @param contaId      a conta para onde o dinheiro vai
 * @param titularNome  o nome do titular, guardado no registro para o "confira o destinatário"
 * @param registradaEm quando foi registrada
 */
public record ChaveRegistrada(ChavePix chave, UUID contaId, String titularNome, Instant registradaEm) {

    /** O DICT permite até 5 chaves por conta de pessoa física. */
    public static final int MAXIMO_POR_CONTA = 5;

    public ChaveRegistrada {
        Objects.requireNonNull(chave, "chave é obrigatória");
        Objects.requireNonNull(contaId, "contaId é obrigatório");
        Objects.requireNonNull(titularNome, "titularNome é obrigatório");
        registradaEm = Objects.requireNonNull(registradaEm, "registradaEm é obrigatório").truncatedTo(ChronoUnit.MICROS);
    }
}
