package io.github.alanjoffre.ledgerly.pix.dominio;

/**
 * O que o antifraude respondeu sobre um Pix, antes do débito (ADR-0033).
 *
 * <ul>
 *   <li>{@link Aprovar} — segue: o próximo passo é o débito;</li>
 *   <li>{@link Segurar} — para antes do débito e espera a analista do backoffice;</li>
 *   <li>{@link Recusar} — não sai: nenhum dinheiro se moveu;</li>
 *   <li>{@link SemResposta} — antifraude fora do ar ou lento. Aqui, ao contrário do razão, não se
 *       repete: o cliente está esperando, e nada se moveu ainda. Vale a regra de valor da falha
 *       segura ({@link TransferenciaPix#LIMITE_SEM_ANTIFRAUDE}).</li>
 * </ul>
 *
 * @see ResultadoDoPasso as respostas do razão
 */
public sealed interface ResultadoDaAvaliacao {

    /** @param nota de 0 a 1000, a do antifraude */
    record Aprovar(int nota) implements ResultadoDaAvaliacao {}

    /** @param motivo os motivos do antifraude, em uma linha */
    record Segurar(int nota, String motivo) implements ResultadoDaAvaliacao {}

    record Recusar(int nota, String motivo) implements ResultadoDaAvaliacao {}

    record SemResposta(String erro) implements ResultadoDaAvaliacao {}
}
