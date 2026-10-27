package io.github.alanjoffre.ledgerly.razao.aplicacao;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import io.github.alanjoffre.ledgerly.razao.dominio.TipoOperacao;
import io.github.alanjoffre.ledgerly.razao.dominio.TipoPartida;
import java.time.Instant;
import java.util.UUID;

/**
 * Uma linha do extrato: modelo de leitura, montado direto da consulta, sem regra de negócio.
 * O extrato que o cliente vê, com "Pix enviado para Maria", vem do serviço extrato (CQRS,
 * ADR-0016); este é o extrato contábil, a fonte da verdade.
 */
public record ItemExtrato(
        UUID lancamentoId,
        TipoOperacao operacao,
        String descricao,
        TipoPartida tipo,
        Dinheiro valor,
        Dinheiro saldoApos,
        Instant registradoEm) {}
