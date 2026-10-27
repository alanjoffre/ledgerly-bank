package io.github.alanjoffre.ledgerly.pix.dominio;

import java.util.Objects;
import java.util.UUID;

/** Uma ponta da transferência: a conta e o nome do titular, como aparecem no comprovante. */
public record Participante(UUID contaId, String nome) {

    public Participante {
        Objects.requireNonNull(contaId, "contaId é obrigatório");
        Objects.requireNonNull(nome, "nome é obrigatório");
    }
}
