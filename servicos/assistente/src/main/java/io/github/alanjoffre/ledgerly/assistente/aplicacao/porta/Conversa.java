package io.github.alanjoffre.ledgerly.assistente.aplicacao.porta;

import io.github.alanjoffre.ledgerly.assistente.aplicacao.FerramentasDoCliente;
import io.github.alanjoffre.ledgerly.assistente.dominio.Trecho;
import java.util.List;
import java.util.Optional;

/**
 * Porta de saída: o modelo de linguagem, que lê a pergunta e os trechos da ajuda, chama as
 * ferramentas que quiser e escreve a resposta.
 *
 * @return vazio quando o modelo não respondeu (fora do ar, lento); nunca lança
 */
public interface Conversa {

    Optional<String> conversar(String pergunta, List<Trecho> trechos, FerramentasDoCliente ferramentas);
}
