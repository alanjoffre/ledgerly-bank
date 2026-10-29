package io.github.alanjoffre.ledgerly.extrato.adaptadores.entrada.web;

import io.github.alanjoffre.ledgerly.extrato.aplicacao.ContasIndisponivelException;
import io.github.alanjoffre.ledgerly.extrato.dominio.ContaNaoEncontradaException;
import io.github.alanjoffre.ledgerly.extrato.dominio.RegraDeNegocioException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Converte exceções em respostas de erro no formato <b>Problem Details</b> (RFC 9457), como nos
 * outros serviços. O {@code type} é estável; a lista está em {@code servicos/extrato/README.md}.
 */
@RestControllerAdvice
class TratadorDeErros extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(TratadorDeErros.class);
    static final String PREFIXO_TIPO = "urn:ledgerly:erro:";

    @ExceptionHandler(RegraDeNegocioException.class)
    ProblemDetail regraDeNegocio(RegraDeNegocioException e) {
        HttpStatus status = e instanceof ContaNaoEncontradaException
                ? HttpStatus.NOT_FOUND
                : HttpStatus.UNPROCESSABLE_CONTENT;
        return problema(status, e.codigo(), e.getMessage());
    }

    /** Parâmetro fora da faixa (limite, antesDe). */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail argumentoInvalido(IllegalArgumentException e) {
        return problema(HttpStatus.BAD_REQUEST, "requisicao-invalida", e.getMessage());
    }

    /** 503 + Retry-After: sem o contas, não dá para saber se a conta é de quem pede. */
    @ExceptionHandler(ContasIndisponivelException.class)
    ResponseEntity<ProblemDetail> contasIndisponivel(ContasIndisponivelException e) {
        LOG.warn("Contas indisponível: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "5")
                .body(problema(HttpStatus.SERVICE_UNAVAILABLE, "contas-indisponivel",
                        "Não foi possível conferir a conta agora. Tente de novo."));
    }

    private static ProblemDetail problema(HttpStatus status, String codigo, String detalhe) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalhe);
        problema.setType(URI.create(PREFIXO_TIPO + codigo));
        problema.setTitle(switch (status) {
            case NOT_FOUND -> "Recurso não encontrado";
            case UNPROCESSABLE_CONTENT -> "Regra de negócio violada";
            case SERVICE_UNAVAILABLE -> "Serviço indisponível";
            default -> "Requisição inválida";
        });
        return problema;
    }
}
