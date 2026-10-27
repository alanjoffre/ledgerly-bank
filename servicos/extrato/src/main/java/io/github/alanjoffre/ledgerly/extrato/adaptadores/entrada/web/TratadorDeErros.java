package io.github.alanjoffre.ledgerly.extrato.adaptadores.entrada.web;

import io.github.alanjoffre.ledgerly.extrato.dominio.ContaNaoEncontradaException;
import io.github.alanjoffre.ledgerly.extrato.dominio.RegraDeNegocioException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Converte exceções em respostas de erro no formato <b>Problem Details</b> (RFC 9457), como nos
 * outros serviços. O {@code type} é estável; a lista está em {@code servicos/extrato/README.md}.
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

    /** Parâmetro fora da faixa (limite, antesDe). */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail argumentoInvalido(IllegalArgumentException e) {
        return problema(HttpStatus.BAD_REQUEST, "requisicao-invalida", e.getMessage());
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
