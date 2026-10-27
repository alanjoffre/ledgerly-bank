package io.github.alanjoffre.ledgerly.pix.dominio;

/**
 * Os tipos de chave Pix para pessoa física, como no DICT (o diretório de chaves do Banco Central).
 *
 * <ul>
 *   <li>{@link #CPF} — 11 dígitos, guardados sem pontuação;</li>
 *   <li>{@link #EMAIL} — em minúsculas, até 77 caracteres;</li>
 *   <li>{@link #TELEFONE} — formato internacional E.164: {@code +55} + DDD + número;</li>
 *   <li>{@link #ALEATORIA} — um UUID gerado pelo banco (no mercado, EVP: <i>endereço virtual de
 *       pagamento</i>), para quem não quer expor dado pessoal.</li>
 * </ul>
 */
public enum TipoChave {
    CPF,
    EMAIL,
    TELEFONE,
    ALEATORIA
}
