package io.github.alanjoffre.ledgerly.assistente.adaptadores.saida.linguagem;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.Set;

/**
 * Embrulha o modelo do Ollama para que toda ferramenta chegue com um esquema de parâmetros completo.
 *
 * <p>O defeito (achado na avaliação de 30/10/2026): para uma ferramenta sem parâmetro, como
 * {@code minhasContas()}, o LangChain4j 1.23 manda {@code "parameters": {"type": "object"}}, sem o
 * campo {@code properties}. O Ollama 0.40, com os modelos que trazem o próprio modelo de conversa
 * (o {@code qwen3:4b-instruct-2507}), recusa o pedido inteiro: "properties must be an object". Como
 * o assistente manda as três ferramentas em toda pergunta, nenhuma pergunta chegava ao modelo, e
 * todas caíam no texto de reserva. O esquema vazio ({@code "properties": {}}) é aceito.
 *
 * <p>Este é um <i>decorator</i> (um objeto que embrulha outro com a mesma interface e muda um
 * detalhe): o resto do pedido passa como veio.
 */
final class ModeloComEsquemaCompleto implements ChatModel {

    private final ChatModel modelo;

    ModeloComEsquemaCompleto(ChatModel modelo) {
        this.modelo = modelo;
    }

    @Override
    public ChatResponse doChat(ChatRequest pedido) {
        List<ToolSpecification> ferramentas = pedido.toolSpecifications();
        if (ferramentas == null || ferramentas.stream().allMatch(f -> f.parameters() != null)) {
            return modelo.doChat(pedido);
        }
        List<ToolSpecification> completas = ferramentas.stream()
                .map(f -> f.parameters() != null ? f : f.toBuilder().parameters(JsonObjectSchema.builder().build()).build())
                .toList();
        ChatRequestParameters parametros = pedido.parameters()
                .overrideWith(ChatRequestParameters.builder().toolSpecifications(completas).build());
        return modelo.doChat(ChatRequest.builder().messages(pedido.messages()).parameters(parametros).build());
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return modelo.defaultRequestParameters();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return modelo.supportedCapabilities();
    }

    @Override
    public ModelProvider provider() {
        return modelo.provider();
    }
}
