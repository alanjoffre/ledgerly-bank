package io.github.alanjoffre.ledgerly.pix.adaptadores.saida.razao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.Razao;
import io.github.alanjoffre.ledgerly.pix.dominio.Passo;
import io.github.alanjoffre.ledgerly.pix.dominio.ResultadoDoPasso;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Adaptador de saída: traduz a resposta HTTP do razão para a linguagem da Saga.
 *
 * <table>
 *   <caption>Status HTTP → resultado do passo</caption>
 *   <tr><th>Resposta</th><th>Resultado</th><th>Por quê</th></tr>
 *   <tr><td>201, 200 (repetido)</td><td>feito</td><td>o dinheiro se moveu (agora ou antes)</td></tr>
 *   <tr><td>400, 404, 422</td><td>recusado</td><td>o razão disse "não": repetir dá o mesmo "não"</td></tr>
 *   <tr><td>409, 429, 5xx, tempo esgotado, conexão recusada</td><td>sem resposta</td>
 *       <td>não se sabe se o dinheiro se moveu: repetir, nunca compensar</td></tr>
 * </table>
 */
@ApplicationScoped
class HttpRazao implements Razao {

    private final RazaoCliente cliente;
    private final ObjectMapper json;

    HttpRazao(@RestClient RazaoCliente cliente, ObjectMapper json) {
        this.cliente = cliente;
        this.json = json;
    }

    @Override
    public ResultadoDoPasso executar(Passo passo, String chave, UUID contaId, Dinheiro valor, String descricao) {
        var corpo = new RazaoCliente.MovimentoPix(contaId, valor.valor().toPlainString(), descricao);
        try (Response resposta = switch (passo) {
            case DEBITAR -> cliente.enviar(chave, corpo);
            case CREDITAR -> cliente.receber(chave, corpo);
            case ESTORNAR -> cliente.estornar(chave, corpo);
        }) {
            return traduzir(passo, resposta);
        } catch (ProcessingException e) {
            // Tempo esgotado ou conexão recusada: o pedido pode ou não ter chegado ao razão.
            return new ResultadoDoPasso.SemResposta(causaRaiz(e));
        }
    }

    private ResultadoDoPasso traduzir(Passo passo, Response resposta) {
        int status = resposta.getStatus();
        JsonNode corpo = ler(resposta);
        if (status == 200 || status == 201) {
            try {
                return new ResultadoDoPasso.Feito(UUID.fromString(corpo.path("id").asText()));
            } catch (IllegalArgumentException e) {
                return new ResultadoDoPasso.SemResposta("o razão respondeu %d sem o id do lançamento".formatted(status));
            }
        }
        if (status == 400 || status == 404 || status == 422) {
            String tipo = corpo.path("type").asText("");
            String codigo = tipo.startsWith("urn:ledgerly:erro:") ? tipo.substring("urn:ledgerly:erro:".length()) : "recusado";
            return new ResultadoDoPasso.Recusado(
                    codigo, motivo(passo, codigo, corpo.path("detail").asText("o razão recusou (HTTP " + status + ")")));
        }
        return new ResultadoDoPasso.SemResposta("HTTP " + status);
    }

    /**
     * O motivo vai para o cliente (na transferência e na linha do extrato): em vez do texto técnico
     * do razão, com o id da conta, a frase que faz sentido para quem pagou.
     */
    private static String motivo(Passo passo, String codigo, String detalheDoRazao) {
        return switch (codigo) {
            case "saldo-insuficiente" -> passo == Passo.DEBITAR ? "saldo insuficiente" : detalheDoRazao;
            case "conta-inativa" -> switch (passo) {
                case DEBITAR -> "a conta do pagador está bloqueada ou encerrada";
                // Conta bloqueada recebe; só a encerrada recusa crédito.
                case CREDITAR -> "a conta de destino está encerrada";
                case ESTORNAR -> "a conta do pagador está encerrada";
            };
            default -> detalheDoRazao;
        };
    }

    private JsonNode ler(Response resposta) {
        try {
            return resposta.hasEntity() ? json.readTree(resposta.readEntity(String.class)) : json.createObjectNode();
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }

    private static String causaRaiz(Throwable erro) {
        Throwable causa = erro;
        while (causa.getCause() != null) {
            causa = causa.getCause();
        }
        return causa.getClass().getSimpleName() + ": " + causa.getMessage();
    }
}
