package io.github.alanjoffre.ledgerly.razao.aplicacao;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import io.github.alanjoffre.ledgerly.razao.dominio.TipoOperacao;
import io.github.alanjoffre.ledgerly.razao.dominio.TipoPartida;
import java.time.Instant;
import java.util.UUID;

/**
 * Uma linha do extrato: modelo de leitura, montado direto da consulta, sem regra de negócio.
 * (Na Fase 2, o extrato do cliente passa a vir do serviço extrato, em CQRS.)
 */
public record ItemExtrato(
        UUID lancamentoId,
        TipoOperacao operacao,
        String descricao,
        TipoPartida tipo,
        Dinheiro valor,
        Dinheiro saldoApos,
        Instant registradoEm) {}
