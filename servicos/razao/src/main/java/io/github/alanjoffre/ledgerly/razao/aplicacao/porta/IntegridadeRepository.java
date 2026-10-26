package io.github.alanjoffre.ledgerly.razao.aplicacao.porta;

import io.github.alanjoffre.ledgerly.razao.aplicacao.RelatorioIntegridade;

/** Porta de saída: consultas de conferência do razão inteiro. */
public interface IntegridadeRepository {

    RelatorioIntegridade verificar();
}
