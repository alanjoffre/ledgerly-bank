package io.github.alanjoffre.ledgerly.assistente.adaptadores.saida.linguagem;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.alanjoffre.ledgerly.assistente.aplicacao.FerramentasDoCliente;

/**
 * As ferramentas do cliente com as anotações que o LangChain4j lê: o nome, a descrição e os
 * parâmetros que o modelo de linguagem vê. Toda a regra (que conta, que período) fica em
 * {@link FerramentasDoCliente}; aqui é só a tradução.
 */
class FerramentasDoModelo {

    private final FerramentasDoCliente ferramentas;

    FerramentasDoModelo(FerramentasDoCliente ferramentas) {
        this.ferramentas = ferramentas;
    }

    @Tool(name = "minhasContas", value = "Lista as contas do cliente (agência e número). Chame antes das outras se não souber o número.")
    String minhasContas() {
        return ferramentas.minhasContas();
    }

    @Tool(name = "saldo", value = "O saldo atual de uma conta do cliente.")
    String saldo(@P(value = "número da conta, como em minhasContas (ex.: 12345-6); vazio se o cliente tem uma conta só", required = false)
            String numeroDaConta) {
        return ferramentas.saldo(numeroDaConta);
    }

    @Tool(name = "lancamentos", value = "Os lançamentos de uma conta do cliente num período, com os totais de entradas e de saídas já somados.")
    String lancamentos(
            @P(value = "número da conta, como em minhasContas; vazio se o cliente tem uma conta só", required = false) String numeroDaConta,
            @P(value = "primeiro dia, AAAA-MM-DD; vazio para 30 dias antes do último", required = false) String de,
            @P(value = "último dia, AAAA-MM-DD; vazio para hoje", required = false) String ate) {
        return ferramentas.lancamentos(numeroDaConta, de, ate);
    }
}
