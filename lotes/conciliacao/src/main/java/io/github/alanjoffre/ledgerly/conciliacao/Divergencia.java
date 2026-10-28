package io.github.alanjoffre.ledgerly.conciliacao;

import java.util.UUID;

/**
 * Um registro em que o Pix e o razão não concordam.
 *
 * @param execucao a execução da conciliação que achou a divergência (a trilha de auditoria)
 * @param tipo     o que não bate
 * @param id       o id do lançamento
 * @param pix      como o Pix o vê; {@code null} se o Pix não o conhece
 * @param razao    como o razão o vê; {@code null} se o razão não o tem
 */
public record Divergencia(String execucao, Tipo tipo, UUID id, Registro pix, Registro razao) {

    public enum Tipo {
        /** O Pix diz que o lançamento existe; o razão não o tem. Dinheiro que o Pix acha que moveu. */
        FALTA_NO_RAZAO,
        /** O razão tem um lançamento de Pix que nenhuma transferência conhece. Dinheiro sem dono. */
        SOBRA_NO_RAZAO,
        /** Os dois têm o lançamento, mas o valor ou o tipo não batem. */
        DIVERGENTE
    }
}
