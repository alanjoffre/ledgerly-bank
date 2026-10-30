package io.github.alanjoffre.ledgerly.antifraude.adaptadores.saida.linguagem;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import io.github.alanjoffre.ledgerly.antifraude.aplicacao.porta.Redator;
import io.github.alanjoffre.ledgerly.antifraude.dominio.Avaliacao;
import io.github.alanjoffre.ledgerly.antifraude.dominio.Decisao;
import io.github.alanjoffre.ledgerly.antifraude.dominio.FatosDoPix;
import io.github.alanjoffre.ledgerly.antifraude.dominio.Motivo;
import io.github.alanjoffre.ledgerly.antifraude.dominio.PoliticaDeRisco;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.Optional;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Adaptador de saída: a explicação escrita pelo modelo de linguagem local, no Ollama, pelo
 * LangChain4j (ADR-0034).
 *
 * <p>O pedido leva só o que a analista já vê na tela — nota, decisão, fatos e motivos. Nenhum id de
 * conta, nenhum nome: o modelo não precisa deles para explicar, e o que não vai não vaza.
 * Temperatura baixa: o texto é para ser fiel aos fatos, não criativo.
 */
@ApplicationScoped
class RedatorOllama implements Redator {

    private static final Logger LOG = Logger.getLogger(RedatorOllama.class);

    static final String INSTRUCOES = """
            Você ajuda a analista de fraude de um banco a entender por que um Pix foi segurado ou \
            recusado pelo antifraude. Escreva de 2 a 3 frases curtas, em português do Brasil, sem \
            listas e sem títulos. Use SOMENTE os fatos e os motivos que vêm na mensagem: não invente \
            número, data, horário, nome nem valor. Não diga se a analista deve liberar ou recusar: \
            explique o que chamou a atenção e por quê, como uma colega experiente explicaria.""";

    private final ChatModel modelo;
    private final String nome;

    RedatorOllama(
            @ConfigProperty(name = "antifraude.linguagem.url") String url,
            @ConfigProperty(name = "antifraude.linguagem.modelo") String nome,
            @ConfigProperty(name = "antifraude.linguagem.tempo-limite") Duration tempoLimite) {
        this.nome = nome;
        this.modelo = OllamaChatModel.builder()
                .baseUrl(url)
                .modelName(nome)
                .temperature(0.2)
                .numPredict(220)
                .timeout(tempoLimite)
                .maxRetries(0)
                .build();
    }

    @Override
    public Optional<String> redigir(Avaliacao avaliacao) {
        try {
            String texto = modelo.chat(SystemMessage.from(INSTRUCOES), UserMessage.from(pedido(avaliacao)))
                    .aiMessage().text();
            return Optional.ofNullable(texto);
        } catch (RuntimeException e) {
            LOG.warnf("O modelo de linguagem não respondeu (%s): vale o texto fixo", e.toString());
            return Optional.empty();
        }
    }

    @Override
    public String modelo() {
        return nome;
    }

    /** Os fatos da avaliação, um por linha, nas palavras da tela do backoffice. */
    static String pedido(Avaliacao a) {
        FatosDoPix f = a.fatos();
        String motivos = a.motivos().isEmpty()
                ? "- nenhum sinal pesou sozinho"
                : a.motivos().stream().map(Motivo::texto).map(t -> "- " + t).collect(Collectors.joining("\n"));
        return """
                Decisão do antifraude: %s (nota %d de 1000).
                Fatos do Pix:
                - valor: %s
                - em relação à média do pagador: %s
                - Pix do pagador na última hora: %d
                - já pagou esta conta antes: %s
                - feito de madrugada (horário de Brasília): %s
                - idade da conta que recebe, em dias: %d
                Motivos:
                %s""".formatted(
                a.decisao() == Decisao.SEGURAR ? "segurado para análise" : "recusado",
                a.nota(),
                PoliticaDeRisco.reais(f.valor()),
                f.razaoDaMedia() == null ? "primeiro Pix do pagador" : PoliticaDeRisco.vezes(f.razaoDaMedia()) + " vezes a média",
                f.pixNaUltimaHora(),
                f.recebedorNovo() ? "não" : "sim",
                f.madrugada() ? "sim" : "não",
                f.idadeDaContaRecebedoraEmDias(),
                motivos);
    }
}
