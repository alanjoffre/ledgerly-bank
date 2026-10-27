package io.github.alanjoffre.ledgerly.razao.adaptadores.entrada.eventos;

import java.io.Serial;

/** A mensagem recebida não segue o contrato (JSON inválido, campo faltando). Tentar de novo não adianta. */
class EventoInvalidoException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    EventoInvalidoException(String mensagem) {
        super(mensagem);
    }
}
