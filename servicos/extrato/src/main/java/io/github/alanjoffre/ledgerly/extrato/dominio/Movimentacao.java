package io.github.alanjoffre.ledgerly.extrato.dominio;

import java.util.Objects;
import java.util.UUID;

/**
 * Uma linha do extrato: um lançamento do razão visto por uma conta.
 *
 * <p>É montada com dois pedaços que chegam por tópicos diferentes do Kafka, em qualquer ordem: a
 * {@link ParteDoRazao} (obrigatória) e a {@link ParteDoPix} (opcional). Identidade: o par
 * lançamento + conta — o mesmo lançamento aparece no extrato do pagador e no do recebedor.
 *
 * @param lancamentoId o lançamento do razão
 * @param contaId      a conta dona desta linha
 * @param razao        o pedaço do razão
 * @param pix          o pedaço do Pix, ou {@code null} se não é Pix ou se ainda não chegou
 */
public record Movimentacao(UUID lancamentoId, UUID contaId, ParteDoRazao razao, ParteDoPix pix) {

    public Movimentacao {
        Objects.requireNonNull(lancamentoId, "lancamentoId é obrigatório");
        Objects.requireNonNull(contaId, "contaId é obrigatório");
        Objects.requireNonNull(razao, "a parte do razão é obrigatória");
    }

    /**
     * O título da linha, como o cliente lê na tela.
     *
     * <p>Com o pedaço do Pix, diz quem é a outra pessoa ("Pix enviado para Maria Souza"). Sem ele,
     * um texto pela operação ("Pix enviado"): o pedaço do Pix pode ainda não ter chegado, e a linha
     * não espera por ele — o dinheiro já se moveu e o cliente precisa ver.
     */
    public String descricao() {
        if (pix != null) {
            return switch (pix.papel()) {
                case ENVIADO -> "Pix enviado para " + pix.contraparte();
                case RECEBIDO -> "Pix recebido de " + pix.contraparte();
                case ESTORNO -> "Estorno do Pix para " + pix.contraparte()
                        + (temTexto(pix.motivo()) ? ": " + pix.motivo() : "");
            };
        }
        return switch (razao.operacao()) {
            case DEPOSITO -> "Depósito";
            case SAQUE -> "Saque";
            case TRANSFERENCIA -> razao.tipo() == TipoPartida.DEBITO ? "Transferência enviada" : "Transferência recebida";
            case PIX_ENVIO -> "Pix enviado";
            case PIX_RECEBIMENTO -> "Pix recebido";
            case PIX_ESTORNO -> "Estorno de Pix";
        };
    }

    private static boolean temTexto(String texto) {
        return texto != null && !texto.isBlank();
    }
}
