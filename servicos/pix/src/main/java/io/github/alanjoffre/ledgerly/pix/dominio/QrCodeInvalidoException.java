package io.github.alanjoffre.ledgerly.pix.dominio;

import java.io.Serial;

/** O texto do QR Code (copia e cola) não é um BR Code válido. */
public class QrCodeInvalidoException extends RegraDeNegocioException {

    @Serial
    private static final long serialVersionUID = 1L;

    public QrCodeInvalidoException(String mensagem) {
        super(mensagem);
    }

    @Override
    public String codigo() {
        return "qrcode-invalido";
    }
}
