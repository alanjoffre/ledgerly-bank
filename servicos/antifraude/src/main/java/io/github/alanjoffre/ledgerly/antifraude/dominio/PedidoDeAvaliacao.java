package io.github.alanjoffre.ledgerly.antifraude.dominio;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * O Pix que o serviço pix quer saber se pode seguir, antes de debitar o pagador.
 *
 * @param transferenciaId a transferência no pix; avaliar de novo a mesma devolve a mesma avaliação
 * @param contaRecebedoraAbertaEm quando a conta que vai receber foi aberta (a conta "laranja" de um
 *     golpe costuma ter poucos dias)
 */
public record PedidoDeAvaliacao(
        UUID transferenciaId,
        UUID pagadorContaId,
        UUID recebedorContaId,
        BigDecimal valor,
        Instant instante,
        Instant contaRecebedoraAbertaEm) {

    public PedidoDeAvaliacao {
        Objects.requireNonNull(transferenciaId, "a transferência é obrigatória");
        Objects.requireNonNull(pagadorContaId, "a conta pagadora é obrigatória");
        Objects.requireNonNull(recebedorContaId, "a conta recebedora é obrigatória");
        Objects.requireNonNull(valor, "o valor é obrigatório");
        Objects.requireNonNull(instante, "o instante é obrigatório");
        Objects.requireNonNull(contaRecebedoraAbertaEm, "a abertura da conta recebedora é obrigatória");
    }
}
