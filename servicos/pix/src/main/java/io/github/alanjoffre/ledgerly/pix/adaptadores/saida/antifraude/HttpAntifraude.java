package io.github.alanjoffre.ledgerly.pix.adaptadores.saida.antifraude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.Antifraude;
import io.github.alanjoffre.ledgerly.pix.dominio.ResultadoDaAvaliacao;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Adaptador de saída: traduz a resposta do antifraude para a linguagem do Pix (ADR-0033).
 *
 * <p>Só um {@code 200} com decisão conhecida vale como resposta. Qualquer outra coisa — tempo
 * esgotado (o limite é curto: o cliente está esperando), conexão recusada, 4xx, 5xx, corpo
 * ilegível — vira "sem resposta", e a transferência decide pela falha segura por valor.
 */
@ApplicationScoped
class HttpAntifraude implements Antifraude {

    private final AntifraudeCliente cliente;
    private final ObjectMapper json;

    HttpAntifraude(@RestClient AntifraudeCliente cliente, ObjectMapper json) {
        this.cliente = cliente;
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
        try (Response resposta = cliente.avaliar(corpo)) {
            if (resposta.getStatus() != 200) {
                return new ResultadoDaAvaliacao.SemResposta("HTTP " + resposta.getStatus());
            }
            return traduzir(json.readTree(resposta.readEntity(String.class)));
        } catch (ProcessingException e) {
            return new ResultadoDaAvaliacao.SemResposta(causaRaiz(e));
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

    private static String causaRaiz(Throwable e) {
        Throwable causa = e;
        while (causa.getCause() != null) {
            causa = causa.getCause();
        }
        return causa.getClass().getSimpleName() + (causa.getMessage() == null ? "" : ": " + causa.getMessage());
    }
}
