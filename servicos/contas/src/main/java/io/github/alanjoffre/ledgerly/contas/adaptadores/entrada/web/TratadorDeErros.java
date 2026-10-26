package io.github.alanjoffre.ledgerly.contas.adaptadores.entrada.web;

import io.github.alanjoffre.ledgerly.contas.aplicacao.ConflitoDeAtualizacaoException;
import io.github.alanjoffre.ledgerly.contas.aplicacao.RazaoIndisponivelException;
import io.github.alanjoffre.ledgerly.contas.dominio.ClienteNaoEncontradoException;
import io.github.alanjoffre.ledgerly.contas.dominio.ContaNaoEncontradaException;
import io.github.alanjoffre.ledgerly.contas.dominio.CpfInvalidoException;
import io.github.alanjoffre.ledgerly.contas.dominio.CpfJaCadastradoException;
import io.github.alanjoffre.ledgerly.contas.dominio.DadoInvalidoException;
import io.github.alanjoffre.ledgerly.contas.dominio.RegraDeNegocioException;
import java.net.URI;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/** Erros em Problem Details (RFC 9457), com {@code type} estável — o mesmo padrão do razão. */
@RestControllerAdvice
class TratadorDeErros extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(TratadorDeErros.class);
    static final String PREFIXO_TIPO = "urn:ledgerly:erro:";

    @ExceptionHandler(RegraDeNegocioException.class)
    ProblemDetail regraDeNegocio(RegraDeNegocioException e) {
        HttpStatus status = switch (e) {
            case ClienteNaoEncontradoException _, ContaNaoEncontradaException _ -> HttpStatus.NOT_FOUND;
            case CpfJaCadastradoException _ -> HttpStatus.CONFLICT;
            case CpfInvalidoException _, DadoInvalidoException _ -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
        return problema(status, e.codigo(), e.getMessage());
    }

    @ExceptionHandler(ConflitoDeAtualizacaoException.class)
    ProblemDetail conflito(ConflitoDeAtualizacaoException e) {
        return problema(HttpStatus.CONFLICT, "conflito-de-atualizacao", e.getMessage());
    }

    /** 503 + Retry-After: o razão está fora; nada mudou aqui, e dá para tentar de novo. */
    @ExceptionHandler(RazaoIndisponivelException.class)
    ResponseEntity<ProblemDetail> razaoIndisponivel(RazaoIndisponivelException e) {
        LOG.warn("Razão indisponível: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(problema(HttpStatus.SERVICE_UNAVAILABLE, "razao-indisponivel",
                        "O serviço de saldo está indisponível no momento; nada foi alterado. Tente de novo."));
    }

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
            case CONFLICT -> "Conflito";
            case UNPROCESSABLE_CONTENT -> "Regra de negócio violada";
            case SERVICE_UNAVAILABLE -> "Serviço indisponível";
            default -> "Requisição inválida";
        });
        return problema;
    }
}
