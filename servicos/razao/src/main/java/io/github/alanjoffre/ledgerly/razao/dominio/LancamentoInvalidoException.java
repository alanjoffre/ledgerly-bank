package io.github.alanjoffre.ledgerly.razao.dominio;

import java.io.Serial;

/** O lançamento quebra uma regra contábil (valor, moeda, partidas dobradas). */
public final class LancamentoInvalidoException extends RegraDeNegocioException {

    @Serial
    private static final long serialVersionUID = 1L;

    public LancamentoInvalidoException(String mensagem) {
        super(mensagem);
    }

    @Override
    public String codigo() {
        return "lancamento-invalido";
    }
}
