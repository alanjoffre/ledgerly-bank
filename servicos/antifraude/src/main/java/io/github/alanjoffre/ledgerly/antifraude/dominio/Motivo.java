package io.github.alanjoffre.ledgerly.antifraude.dominio;

/**
 * Um porquê da nota, em português, com os números do próprio Pix — para a analista do backoffice
 * entender sem saber o que é um modelo de ML.
 *
 * @param codigo {@code regra:...} (uma regra explícita) ou {@code modelo:...} (um sinal que pesou)
 */
public record Motivo(String codigo, String texto) {}
