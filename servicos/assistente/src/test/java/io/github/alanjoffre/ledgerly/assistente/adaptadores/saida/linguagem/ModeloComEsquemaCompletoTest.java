package io.github.alanjoffre.ledgerly.assistente.adaptadores.saida.linguagem;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.alanjoffre.ledgerly.assistente.aplicacao.BancarioFalso;
import io.github.alanjoffre.ledgerly.assistente.aplicacao.Cliente;
import io.github.alanjoffre.ledgerly.assistente.aplicacao.FerramentasDoCliente;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** O esquema das ferramentas que chega ao Ollama: toda ferramenta com {@code properties}. */
@DisplayName("Modelo com o esquema das ferramentas completo")
class ModeloComEsquemaCompletoTest {

    /** Guarda o que chegaria ao Ollama e responde um texto fixo. */
    private static final class ModeloQueGuarda implements ChatModel {
        final List<ChatRequest> pedidos = new ArrayList<>();

        @Override
        public ChatResponse doChat(ChatRequest pedido) {
            pedidos.add(pedido);
            return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
        }
    }

    private final ModeloQueGuarda ollama = new ModeloQueGuarda();
    private final ChatModel modelo = new ModeloComEsquemaCompleto(ollama);

    private static List<ToolSpecification> asTresFerramentas() {
        var ferramentas = new FerramentasDoCliente(new BancarioFalso(), new Cliente(UUID.randomUUID(), "token"),
                LocalDate.parse("2026-10-28"));
        return ToolSpecifications.toolSpecificationsFrom(new FerramentasDoModelo(ferramentas));
    }

    @Test
    @DisplayName("o LangChain4j deixa sem esquema a ferramenta sem parâmetro (é o defeito que o Ollama recusa)")
    void oDefeito() {
        assertThat(asTresFerramentas())
                .filteredOn(f -> f.name().equals("minhasContas"))
                .singleElement()
                .satisfies(f -> assertThat(f.parameters()).isNull());
    }

    @Test
    @DisplayName("a ferramenta sem parâmetro chega ao Ollama com um esquema vazio, e as outras como vieram")
    void completaOEsquema() {
        List<ToolSpecification> originais = asTresFerramentas();

        modelo.chat(ChatRequest.builder()
                .messages(UserMessage.from("Quais são minhas contas?"))
                .parameters(ChatRequestParameters.builder().toolSpecifications(originais).build())
                .build());

        // A ordem das ferramentas vem da reflexão (getDeclaredMethods), que a JVM não garante: o teste
        // compara com a ordem de entrada, e não com uma ordem fixa.
        List<ToolSpecification> enviadas = ollama.pedidos.getFirst().toolSpecifications();
        assertThat(enviadas).extracting(ToolSpecification::name)
                .as("a ordem e os nomes não mudam")
                .containsExactlyElementsOf(originais.stream().map(ToolSpecification::name).toList());
        assertThat(enviadas).allSatisfy(f -> assertThat(f.parameters()).isNotNull());
        for (int i = 0; i < originais.size(); i++) {
            if (originais.get(i).name().equals("minhasContas")) {
                assertThat(enviadas.get(i).parameters().properties()).isEmpty();
            } else {
                assertThat(enviadas.get(i)).as("a ferramenta com parâmetros passa como veio").isSameAs(originais.get(i));
            }
        }
        assertThat(ollama.pedidos.getFirst().messages()).hasSize(1);
    }

    @Test
    @DisplayName("sem ferramenta, o pedido passa como veio")
    void semFerramenta() {
        ChatRequest pedido = ChatRequest.builder().messages(UserMessage.from("Oi")).build();

        modelo.chat(pedido);

        assertThat(ollama.pedidos.getFirst().messages()).isEqualTo(pedido.messages());
        assertThat(ollama.pedidos.getFirst().toolSpecifications()).isNullOrEmpty();
    }
}
