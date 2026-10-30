package io.github.alanjoffre.ledgerly.assistente.dominio;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Uma linha do extrato, só com o que a resposta pode precisar.
 *
 * @param valor     sempre positivo; {@code credito} diz o sentido
 * @param descricao o título da linha no extrato ("Pix enviado para Maria Souza")
 */
public record Lancamento(Instant registradoEm, boolean credito, BigDecimal valor, BigDecimal saldoApos, String descricao) {}
