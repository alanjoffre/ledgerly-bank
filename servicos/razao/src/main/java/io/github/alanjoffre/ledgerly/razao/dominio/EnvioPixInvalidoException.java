package io.github.alanjoffre.ledgerly.razao.dominio;

import java.io.Serial;

/**
 * O recebimento ou o estorno de um Pix aponta para um envio que não serve: não existe, não é um
 * envio de Pix, tem outro valor, já teve a sua saída da Liquidação Pix, ou (no estorno) o dinheiro
 * voltaria para quem não pagou. Ver ADR-0019.
 */
public final class EnvioPixInvalidoException extends RegraDeNegocioException {

    @Serial
    private static final long serialVersionUID = 1L;

    public EnvioPixInvalidoException(String mensagem) {
        super(mensagem);
    }

    @Override
    public String codigo() {
        return "envio-pix-invalido";
    }
}
