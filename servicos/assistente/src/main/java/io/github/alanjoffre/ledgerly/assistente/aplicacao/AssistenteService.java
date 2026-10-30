package io.github.alanjoffre.ledgerly.assistente.aplicacao;

import io.github.alanjoffre.ledgerly.assistente.aplicacao.porta.Bancario;
import io.github.alanjoffre.ledgerly.assistente.aplicacao.porta.BaseDeAjuda;
import io.github.alanjoffre.ledgerly.assistente.aplicacao.porta.Conversa;
import io.github.alanjoffre.ledgerly.assistente.dominio.GuardrailDeEntrada;
import io.github.alanjoffre.ledgerly.assistente.dominio.GuardrailDeSaida;
import io.github.alanjoffre.ledgerly.assistente.dominio.Resposta;
import io.github.alanjoffre.ledgerly.assistente.dominio.Trecho;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Caso de uso: responder a pergunta do cliente (ADR-0036).
 *
 * <ol>
 *   <li>trava de entrada — barrou, responde a recusa fixa, e o modelo nem vê;</li>
 *   <li>busca os trechos da ajuda mais parecidos com a pergunta (RAG); a busca fora do ar não
 *       derruba a resposta, só vai sem ajuda;</li>
 *   <li>o modelo de linguagem responde, chamando as ferramentas do cliente se precisar;</li>
 *   <li>trava de saída — todo valor em reais tem de ter vindo das ferramentas ou dos trechos.
 *       Barrou, ou o modelo não respondeu: vale o texto fixo de reserva.</li>
 * </ol>
 */
@Service
public class AssistenteService {

    private static final System.Logger LOG = System.getLogger(AssistenteService.class.getName());
    static final int TRECHOS = 3;

    private final BaseDeAjuda ajuda;
    private final Conversa conversa;
    private final Bancario bancario;
    private final Clock relogio;

    public AssistenteService(BaseDeAjuda ajuda, Conversa conversa, Bancario bancario, Clock relogio) {
        this.ajuda = ajuda;
        this.conversa = conversa;
        this.bancario = bancario;
        this.relogio = relogio;
    }

    public Resposta responder(Cliente cliente, String pergunta) {
        Optional<String> barrada = GuardrailDeEntrada.problema(pergunta);
        if (barrada.isPresent()) {
            LOG.log(System.Logger.Level.INFO, "Pergunta barrada na entrada ({0}): {1}", barrada.get(), cliente);
            return Resposta.recusa();
        }
        String texto = pergunta.strip();
        List<Trecho> trechos = trechos(texto);
        List<String> fontes = trechos.stream().map(Trecho::titulo).distinct().toList();
        var ferramentas = new FerramentasDoCliente(bancario, cliente, LocalDate.now(relogio.withZone(FerramentasDoCliente.BRASILIA)));

        Optional<String> escrita = conversa.conversar(texto, trechos, ferramentas).map(String::strip);
        if (escrita.isEmpty()) {
            return Resposta.reserva(fontes);
        }
        Optional<String> problema = GuardrailDeSaida.problema(escrita.get(), ferramentas.consultas(), trechos);
        if (problema.isPresent()) {
            LOG.log(System.Logger.Level.WARNING, "Resposta barrada na saída ({0}): vale o texto de reserva", problema.get());
            return Resposta.reserva(fontes);
        }
        return new Resposta(escrita.get(), Resposta.Origem.MODELO_DE_LINGUAGEM, fontes);
    }

    private List<Trecho> trechos(String pergunta) {
        try {
            return ajuda.buscar(pergunta, TRECHOS);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "A busca na ajuda falhou ({0}): a pergunta segue sem ajuda", e.toString());
            return List.of();
        }
    }
}
