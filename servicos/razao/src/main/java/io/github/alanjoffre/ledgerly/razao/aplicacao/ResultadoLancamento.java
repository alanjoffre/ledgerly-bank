package io.github.alanjoffre.ledgerly.razao.aplicacao;

import io.github.alanjoffre.ledgerly.razao.dominio.Lancamento;

/**
 * @param lancamento o lançamento (novo ou o já existente)
 * @param repetido   {@code true} se a chave de idempotência já tinha sido usada para o mesmo
 *                   pedido: nada foi feito agora, e este é o lançamento original
 */
public record ResultadoLancamento(Lancamento lancamento, boolean repetido) {}
