package io.github.alanjoffre.ledgerly.pix.aplicacao;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import io.github.alanjoffre.ledgerly.pix.dominio.BrCode;
import io.github.alanjoffre.ledgerly.pix.dominio.ChaveRegistrada;
import jakarta.enterprise.context.ApplicationScoped;

/** Gera e lê o QR Code do Pix (BR Code estático). */
@ApplicationScoped
public class QrCodeService {

    /** A cidade do recebedor no QR Code. O Ledgerly é fictício; a sede, também. */
    static final String CIDADE = "SAO PAULO";

    private final ChaveService chaves;

    public QrCodeService(ChaveService chaves) {
        this.chaves = chaves;
    }

    /** @param valor o valor fixo, ou {@code null} para quem paga digitar */
    public BrCode gerar(String chave, Dinheiro valor, String descricao, Acesso acesso) {
        ChaveRegistrada registrada = chaves.doDono(chave, acesso);
        return new BrCode(registrada.chave(), valor, registrada.titularNome(), CIDADE, descricao);
    }

    public BrCode ler(String copiaECola) {
        return BrCode.ler(copiaECola);
    }
}
