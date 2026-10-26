package io.github.alanjoffre.ledgerly.razao.adaptadores.entrada.web;

import io.github.alanjoffre.ledgerly.nucleo.MoedasDiferentesException;
import io.github.alanjoffre.ledgerly.razao.dominio.ContaNaoEncontradaException;
import io.github.alanjoffre.ledgerly.razao.dominio.RegraDeNegocioException;
import java.net.URI;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Converte exceções em respostas de erro no formato <b>Problem Details</b> (RFC 9457), o padrão
 * da web para erros de API:
 *
 * <pre>{@code
 * HTTP 422
 * { "type": "urn:ledgerly:erro:saldo-insuficiente",
 *   "title": "Regra de negócio violada",
 *   "status": 422,
 *   "detail": "Saldo insuficiente na conta ...: saldo BRL 10.00, débito de BRL 50.00" }
 * }</pre>
 *
 * <p>O {@code type} é estável e é por ele que o cliente da API decide o que fazer. Lista completa
 * em {@code servicos/razao/README.md}.
 */
@RestControllerAdvice
class TratadorDeErros extends ResponseEntityExceptionHandler {

    static final String PREFIXO_TIPO = "urn:ledgerly:erro:";

    @ExceptionHandler(RegraDeNegocioException.class)
    ProblemDetail regraDeNegocio(RegraDeNegocioException e) {
        HttpStatus status = e instanceof ContaNaoEncontradaException
                ? HttpStatus.NOT_FOUND
                : HttpStatus.UNPROCESSABLE_CONTENT;
        return problema(status, e.codigo(), e.getMessage());
    }

    @ExceptionHandler(MoedasDiferentesException.class)
    ProblemDetail moedasDiferentes(MoedasDiferentesException e) {
        return problema(HttpStatus.UNPROCESSABLE_CONTENT, "lancamento-invalido", e.getMessage());
    }

    /** Parâmetro fora da faixa (por exemplo, limite do extrato). */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail argumentoInvalido(IllegalArgumentException e) {
        return problema(HttpStatus.BAD_REQUEST, "requisicao-invalida", e.getMessage());
    }

    /** Erro de validação do corpo: lista cada campo com o motivo, em {@code campos}. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> campos = new TreeMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(erro -> campos.put(erro.getField(), erro.getDefaultMessage()));
        ProblemDetail problema = problema(HttpStatus.BAD_REQUEST, "requisicao-invalida", "Há campos inválidos na requisição");
        problema.setProperty("campos", campos);
        return ResponseEntity.badRequest().body(problema);
    }

    private static ProblemDetail problema(HttpStatus status, String codigo, String detalhe) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalhe);
        problema.setType(URI.create(PREFIXO_TIPO + codigo));
        problema.setTitle(switch (status) {
            case NOT_FOUND -> "Recurso não encontrado";
            case UNPROCESSABLE_CONTENT -> "Regra de negócio violada";
            default -> "Requisição inválida";
        });
        return problema;
    }
}
