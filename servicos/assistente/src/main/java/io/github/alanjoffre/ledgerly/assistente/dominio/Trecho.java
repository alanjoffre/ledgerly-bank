package io.github.alanjoffre.ledgerly.assistente.dominio;

/**
 * Um pedaço de artigo de ajuda, achado pela busca por significado (RAG).
 *
 * @param artigo o arquivo do artigo ({@code limites-do-pix})
 * @param titulo o título que a tela mostra como fonte
 * @param texto  a seção do artigo que vai para o modelo de linguagem
 */
public record Trecho(String artigo, String titulo, String texto) {}
