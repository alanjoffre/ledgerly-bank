package io.github.alanjoffre.ledgerly.pix.adaptadores.entrada.web;

import com.fasterxml.jackson.core.JacksonException;
import io.github.alanjoffre.ledgerly.pix.aplicacao.PedidoEmAndamentoException;
import io.github.alanjoffre.ledgerly.pix.aplicacao.ServicoIndisponivelException;
import io.github.alanjoffre.ledgerly.pix.dominio.RegraDeNegocioException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.TreeMap;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Transforma exceções em respostas de erro no formato Problem Details (RFC 9457), o mesmo dos
 * serviços Spring:
 *
 * <pre>{ "type": "urn:ledgerly:erro:chave-ja-cadastrada", "title": "Conflito", "status": 409, "detail": "..." }</pre>
 *
 * <p>{@code @ServerExceptionMapper} é o equivalente Quarkus do {@code @ExceptionHandler} do Spring.
 */
class TratadorDeErros {

    private static final Logger LOG = Logger.getLogger(TratadorDeErros.class);
    private static final String TIPO_PROBLEMA = "application/problem+json";

    /** O status HTTP de cada código de regra de negócio; o que não está aqui é 422. */
    private static final Map<String, Integer> STATUS = Map.of(
            "chave-invalida", 400,
            "qrcode-invalido", 400,
            "requisicao-invalida", 400,
            "chave-nao-encontrada", 404,
            "conta-nao-encontrada", 404,
            "transferencia-nao-encontrada", 404,
            "chave-ja-cadastrada", 409);

    @ServerExceptionMapper
    Response regraDeNegocio(RegraDeNegocioException e) {
        return problema(STATUS.getOrDefault(e.codigo(), 422), e.codigo(), e.getMessage(), null).build();
    }

    @ServerExceptionMapper
    Response pedidoEmAndamento(PedidoEmAndamentoException e) {
        return problema(409, e.codigo(), e.getMessage(), null).header("Retry-After", "1").build();
    }

    @ServerExceptionMapper
    Response servicoIndisponivel(ServicoIndisponivelException e) {
        LOG.warn(e.getMessage());
        return problema(503, e.codigo(), e.getMessage(), null).header("Retry-After", "5").build();
    }

    @ServerExceptionMapper
    Response validacao(ConstraintViolationException e) {
        Map<String, String> campos = new TreeMap<>();
        for (ConstraintViolation<?> v : e.getConstraintViolations()) {
            String caminho = v.getPropertyPath().toString();
            campos.put(caminho.substring(caminho.lastIndexOf('.') + 1), v.getMessage());
        }
        return problema(400, "requisicao-invalida", "Há campos inválidos na requisição", campos).build();
    }

    @ServerExceptionMapper
    Response jsonInvalido(JacksonException e) {
        return problema(400, "requisicao-invalida", "O corpo da requisição não é um JSON válido para esta operação", null)
                .build();
    }

    @ServerExceptionMapper
    Response web(WebApplicationException e) {
        int status = e.getResponse().getStatus();
        String codigo = switch (status) {
            case 404 -> "nao-encontrado";
            case 405 -> "metodo-nao-permitido";
            case 415 -> "tipo-de-conteudo-nao-suportado";
            default -> "requisicao-invalida";
        };
        return problema(status, codigo, e.getMessage(), null).build();
    }

    @ServerExceptionMapper
    Response inesperado(RuntimeException e) {
        LOG.error("Erro inesperado", e);
        return problema(500, "erro-interno", "Erro inesperado no serviço Pix", null).build();
    }

    private static Response.ResponseBuilder problema(int status, String codigo, String detalhe, Map<String, String> campos) {
        Map<String, Object> corpo = new TreeMap<>();
        corpo.put("type", "urn:ledgerly:erro:" + codigo);
        corpo.put("title", titulo(status));
        corpo.put("status", status);
        corpo.put("detail", detalhe);
        if (campos != null) {
            corpo.put("campos", campos);
        }
        return Response.status(status).type(TIPO_PROBLEMA).entity(corpo);
    }

    private static String titulo(int status) {
        return switch (status) {
            case 400 -> "Requisição inválida";
            case 404 -> "Não encontrado";
            case 409 -> "Conflito";
            case 422 -> "Regra de negócio";
            case 503 -> "Serviço indisponível";
            default -> "Erro";
        };
    }
}
