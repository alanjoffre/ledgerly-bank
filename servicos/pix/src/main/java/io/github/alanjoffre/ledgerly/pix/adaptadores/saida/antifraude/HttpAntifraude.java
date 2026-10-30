package io.github.alanjoffre.ledgerly.pix.adaptadores.saida.antifraude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.Antifraude;
import io.github.alanjoffre.ledgerly.pix.dominio.ResultadoDaAvaliacao;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;

/**
 * Adaptador de saída: traduz a resposta do antifraude para a linguagem do Pix (ADR-0033).
 *
 * <p>Só um {@code 200} com decisão conhecida vale como resposta. Qualquer outra coisa — tempo
 * esgotado (o limite é curto: o cliente está esperando), conexão recusada, 4xx, 5xx, corpo
 * ilegível, circuito aberto — vira "sem resposta", e a transferência decide pela falha segura por
 * valor.
 */
@ApplicationScoped
class HttpAntifraude implements Antifraude {

    private final ChamadaAoAntifraude chamada;
    private final ObjectMapper json;

    HttpAntifraude(ChamadaAoAntifraude chamada, ObjectMapper json) {
        this.chamada = chamada;
        this.json = json;
    }

    @Override
    public ResultadoDaAvaliacao avaliar(Pedido p) {
        var corpo = new AntifraudeCliente.PedidoDeAvaliacao(
                p.transferenciaId(),
                p.pagadorContaId(),
                p.recebedorContaId(),
                p.valor().valor().toPlainString(),
                p.instante().toString(),
                p.contaRecebedoraAbertaEm().toString());
        try {
            ChamadaAoAntifraude.Resposta resposta = chamada.chamar(corpo);
            if (resposta.status() != 200) {
                return new ResultadoDaAvaliacao.SemResposta("HTTP " + resposta.status());
            }
            return traduzir(json.readTree(resposta.corpo()));
        } catch (CircuitBreakerOpenException e) {
            return new ResultadoDaAvaliacao.SemResposta("circuito aberto");
        } catch (ChamadaAoAntifraude.AntifraudeIndisponivel e) {
            return new ResultadoDaAvaliacao.SemResposta(e.getMessage());
        } catch (Exception e) {
            return new ResultadoDaAvaliacao.SemResposta("resposta ilegível: " + e.getMessage());
        }
    }

    private static ResultadoDaAvaliacao traduzir(JsonNode corpo) {
        int nota = corpo.path("nota").asInt(-1);
        String decisao = corpo.path("decisao").asText("");
        if (nota < 0 || nota > 1000) {
            return new ResultadoDaAvaliacao.SemResposta("nota fora de 0 a 1000");
        }
        return switch (decisao) {
            case "APROVAR" -> new ResultadoDaAvaliacao.Aprovar(nota);
            case "SEGURAR" -> new ResultadoDaAvaliacao.Segurar(nota, motivos(corpo));
            case "RECUSAR" -> new ResultadoDaAvaliacao.Recusar(nota, motivos(corpo));
            default -> new ResultadoDaAvaliacao.SemResposta("decisão desconhecida: " + decisao);
        };
    }

    /** Os motivos do antifraude numa linha: "valor de R$ 4.800,00; primeiro Pix do pagador para esta conta". */
    private static String motivos(JsonNode corpo) {
        List<String> textos = new ArrayList<>();
        corpo.path("motivos").forEach(m -> textos.add(m.path("texto").asText()));
        return textos.isEmpty() ? "sem motivo informado" : String.join("; ", textos);
    }
}
