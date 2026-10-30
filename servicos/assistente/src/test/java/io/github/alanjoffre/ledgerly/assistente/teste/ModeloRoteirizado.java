package io.github.alanjoffre.ledgerly.assistente.teste;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * O modelo de linguagem dos testes: segue um roteiro — "peça a ferramenta X", "responda Y" — e
 * guarda cada pedido que recebeu. Determinístico; é o que roda no CI (o modelo de verdade só nas
 * evals, ADR-0037). O LangChain4j de verdade fica no meio: executa as ferramentas pedidas e devolve
 * o resultado, como faria com o Ollama.
 */
public class ModeloRoteirizado implements ChatModel {

    private final Deque<Function<ChatRequest, AiMessage>> roteiro = new ArrayDeque<>();
    private final List<ChatRequest> pedidos = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger ids = new AtomicInteger();
    private volatile boolean foraDoAr;

    public synchronized void zerar() {
        roteiro.clear();
        pedidos.clear();
        foraDoAr = false;
    }

    /** O próximo passo: pedir a ferramenta, com os argumentos em JSON. */
    public synchronized ModeloRoteirizado pedir(String ferramenta, String argumentos) {
        roteiro.add(pedido -> AiMessage.from(ToolExecutionRequest.builder()
                .id("chamada-" + ids.incrementAndGet()).name(ferramenta).arguments(argumentos).build()));
        return this;
    }

    public synchronized ModeloRoteirizado responder(String texto) {
        roteiro.add(pedido -> AiMessage.from(texto));
        return this;
    }

    /** Responde com um texto feito a partir do que as ferramentas devolveram até aqui. */
    public synchronized ModeloRoteirizado responderCom(Function<List<String>, String> texto) {
        roteiro.add(pedido -> AiMessage.from(texto.apply(resultados(pedido))));
        return this;
    }

    public void foraDoAr(boolean fora) {
        foraDoAr = fora;
    }

    public List<ChatRequest> pedidos() {
        return List.copyOf(pedidos);
    }

    public static List<String> resultados(ChatRequest pedido) {
        return pedido.messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(m -> ((ToolExecutionResultMessage) m).text())
                .toList();
    }

    public static String texto(ChatMessage mensagem) {
        return switch (mensagem) {
            case dev.langchain4j.data.message.SystemMessage s -> s.text();
            case dev.langchain4j.data.message.UserMessage u -> u.singleText();
            case ToolExecutionResultMessage t -> t.text();
            case AiMessage a -> a.text();
            default -> mensagem.toString();
        };
    }

    @Override
    public synchronized ChatResponse doChat(ChatRequest pedido) {
        pedidos.add(pedido);
        if (foraDoAr) {
            throw new IllegalStateException("Ollama fora do ar (teste)");
        }
        Function<ChatRequest, AiMessage> passo = roteiro.poll();
        if (passo == null) {
            throw new IllegalStateException("O roteiro acabou: o teste não disse o que responder");
        }
        return ChatResponse.builder().aiMessage(passo.apply(pedido)).build();
    }
}
