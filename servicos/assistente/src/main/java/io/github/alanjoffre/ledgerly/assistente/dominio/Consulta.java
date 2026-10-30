package io.github.alanjoffre.ledgerly.assistente.dominio;

/**
 * O que uma ferramenta devolveu ao modelo de linguagem: a prova de onde veio cada valor da resposta.
 *
 * @param ferramenta o nome da ferramenta ({@code saldo})
 * @param resultado  o texto que o modelo recebeu
 */
public record Consulta(String ferramenta, String resultado) {}
