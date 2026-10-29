package io.github.alanjoffre.ledgerly.pix.aplicacao;

import java.util.UUID;

/**
 * Quem pede pode mexer no que é deste cliente? A autorização por objeto (ADR-0026), como nos
 * serviços Spring: o cliente só vê e movimenta o que é dele; o recurso de outro responde como se
 * não existisse (404).
 *
 * <p>A aplicação não conhece token nem papel: o adaptador web monta o {@code Acesso} a partir do
 * token, e a aplicação só pergunta.
 */
public interface Acesso {

    /** Analista e serviço: não há dono a conferir. */
    Acesso TOTAL = new Acesso() {
        @Override
        public boolean podeUsar(UUID clienteId) {
            return true;
        }

        @Override
        public boolean semDono() {
            return true;
        }
    };

    /** O cliente {@code clienteId} é quem pede? */
    boolean podeUsar(UUID clienteId);

    /** Não há dono a conferir (a aplicação pode pular a consulta ao serviço de contas). */
    default boolean semDono() {
        return false;
    }
}
