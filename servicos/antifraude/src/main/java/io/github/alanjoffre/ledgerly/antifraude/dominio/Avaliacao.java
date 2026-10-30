package io.github.alanjoffre.ledgerly.antifraude.dominio;

import java.time.Instant;
import java.util.List;

/**
 * A avaliação de risco de um Pix: a nota, a decisão e os motivos, com tudo o que entrou nela.
 * Fica gravada: dá para responder, meses depois, por que um Pix foi retido e por qual modelo.
 *
 * @param nota de 0 (nenhum risco) a 1000
 */
public record Avaliacao(
        PedidoDeAvaliacao pedido,
        FatosDoPix fatos,
        int nota,
        Decisao decisao,
        List<Motivo> motivos,
        String modelo,
        Instant avaliadaEm) {

    public Avaliacao {
        if (nota < 0 || nota > PoliticaDeRisco.NOTA_MAXIMA) {
            throw new IllegalArgumentException("nota fora de 0 a 1000: " + nota);
        }
        motivos = List.copyOf(motivos);
    }
}
