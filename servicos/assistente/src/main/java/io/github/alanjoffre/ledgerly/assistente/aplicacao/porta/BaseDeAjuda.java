package io.github.alanjoffre.ledgerly.assistente.aplicacao.porta;

import io.github.alanjoffre.ledgerly.assistente.dominio.Trecho;
import java.util.List;

/** Porta de saída: os trechos dos artigos de ajuda mais parecidos com a pergunta (RAG). */
public interface BaseDeAjuda {

    List<Trecho> buscar(String pergunta, int quantos);
}
