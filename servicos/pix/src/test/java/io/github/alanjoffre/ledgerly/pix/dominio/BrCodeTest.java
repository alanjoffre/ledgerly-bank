package io.github.alanjoffre.ledgerly.pix.dominio;

import static io.github.alanjoffre.ledgerly.nucleo.Dinheiro.reais;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QR Code do Pix (BR Code)")
class BrCodeTest {

    private static final ChavePix CHAVE = new ChavePix(TipoChave.EMAIL, "maria@exemplo.com");

    @Test
    @DisplayName("CRC16-CCITT: o valor de referência do algoritmo (\"123456789\" → 29B1)")
    void crcDeReferencia() {
        assertThat(Crc16.calcular("123456789")).isEqualTo("29B1");
    }

    @Test
    @DisplayName("monta os campos na ordem do padrão e termina com o CRC")
    void formato() {
        String texto = new BrCode(CHAVE, reais("10.50"), "Maria Souza", "São Paulo", null).copiaECola();

        assertThat(texto).startsWith("000201" + "2639" + "0014br.gov.bcb.pix" + "0117maria@exemplo.com");
        assertThat(texto).contains("52040000", "5303986", "540510.50", "5802BR", "5911MARIA SOUZA", "6009SAO PAULO", "62070503***");
        assertThat(texto).containsPattern("6304[0-9A-F]{4}$");
        assertThat(texto.substring(texto.length() - 4)).isEqualTo(Crc16.calcular(texto.substring(0, texto.length() - 4)));
    }

    @Test
    @DisplayName("ida e volta: o que se gera é o que se lê")
    void idaEVolta() {
        BrCode original = new BrCode(CHAVE, reais("1234.56"), "José da Conceição", "Florianópolis", "Aluguel de março");
        BrCode lido = BrCode.ler(original.copiaECola());

        assertThat(lido).isEqualTo(original);
        assertThat(lido.nomeRecebedor()).isEqualTo("JOSE DA CONCEICAO");
        assertThat(lido.cidade()).isEqualTo("FLORIANOPOLIS");
        assertThat(lido.descricao()).isEqualTo("Aluguel de marco");
        assertThat(lido.valorFixo()).contains(reais("1234.56"));
    }

    @Test
    @DisplayName("sem valor: quem paga digita")
    void semValor() {
        BrCode lido = BrCode.ler(new BrCode(CHAVE, null, "Maria", "Recife", null).copiaECola());
        assertThat(lido.valorFixo()).isEmpty();
    }

    @Test
    @DisplayName("nome e cidade longos são cortados no limite do padrão (25 e 15)")
    void limites() {
        BrCode qr = new BrCode(CHAVE, null, "Maria Aparecida dos Santos Oliveira", "Santo Antônio do Descoberto", null);
        assertThat(qr.nomeRecebedor()).hasSizeLessThanOrEqualTo(25);
        assertThat(qr.cidade()).hasSizeLessThanOrEqualTo(15);
        assertThat(BrCode.ler(qr.copiaECola())).isEqualTo(qr);
    }

    @Test
    @DisplayName("QR Code adulterado (o valor trocado de 10.50 para 99.50): recusado pelo CRC")
    void adulterado() {
        String texto = new BrCode(CHAVE, reais("10.50"), "Maria", "Recife", null).copiaECola();
        String adulterado = texto.replace("540510.50", "540599.50");

        assertThatThrownBy(() -> BrCode.ler(adulterado))
                .isInstanceOf(QrCodeInvalidoException.class)
                .hasMessageContaining("CRC");
    }

    @Test
    @DisplayName("texto que não é BR Code, ou de outra moeda, ou sem campo obrigatório: recusado")
    void invalidos() {
        assertThatThrownBy(() -> BrCode.ler(null)).isInstanceOf(QrCodeInvalidoException.class);
        assertThatThrownBy(() -> BrCode.ler("qualquer coisa")).isInstanceOf(QrCodeInvalidoException.class);
        String contaPix = campo("26", campo("00", "br.gov.bcb.pix") + campo("01", "m@exemplo.com"));
        String contaOutra = campo("26", campo("00", "br.gov.bcb.xxx") + campo("01", "m@exemplo.com"));
        String resto = campo("58", "BR") + campo("59", "M") + campo("60", "R");
        assertThatThrownBy(() -> BrCode.ler(comCrc(campo("00", "01") + contaOutra + campo("53", "986") + resto + "6304")))
                .isInstanceOf(QrCodeInvalidoException.class).hasMessageContaining("não é de Pix");
        assertThatThrownBy(() -> BrCode.ler(comCrc(campo("00", "01") + contaPix + campo("53", "840") + resto + "6304")))
                .isInstanceOf(QrCodeInvalidoException.class).hasMessageContaining("reais");
        assertThatThrownBy(() -> BrCode.ler(comCrc(
                        campo("00", "01") + contaPix + campo("53", "986") + campo("58", "BR") + campo("60", "R") + "6304")))
                .isInstanceOf(QrCodeInvalidoException.class).hasMessageContaining("59");
        assertThatThrownBy(() -> BrCode.ler(comCrc(campo("00", "01") + "6304")))
                .isInstanceOf(QrCodeInvalidoException.class).hasMessageContaining("26");
        assertThatThrownBy(() -> BrCode.ler(comCrc("00990" + "6304")))
                .isInstanceOf(QrCodeInvalidoException.class);
        assertThatThrownBy(() -> BrCode.ler(comCrc("00xx01" + "6304")))
                .isInstanceOf(QrCodeInvalidoException.class);
        assertThatThrownBy(() -> BrCode.ler(comCrc(campo("00", "01") + contaPix + campo("53", "986") + campo("54", "abc") + resto + "6304")))
                .isInstanceOf(QrCodeInvalidoException.class).hasMessageContaining("valor");
        String chaveRuim = campo("26", campo("00", "br.gov.bcb.pix") + campo("01", "sem-arroba-nem-cpf"));
        assertThatThrownBy(() -> BrCode.ler(comCrc(campo("00", "01") + chaveRuim + campo("53", "986") + resto + "6304")))
                .isInstanceOf(QrCodeInvalidoException.class).hasMessageContaining("chave");
        assertThatThrownBy(() -> BrCode.ler(campo("00", "01") + "63041234"))
                .isInstanceOf(QrCodeInvalidoException.class);
        assertThatThrownBy(() -> BrCode.ler(campo("00", "01") + "99041234"))
                .isInstanceOf(QrCodeInvalidoException.class).hasMessageContaining("CRC");
    }

    @Test
    @DisplayName("nome sem nenhuma letra: recusado ao gerar")
    void nomeVazio() {
        assertThatThrownBy(() -> new BrCode(CHAVE, null, "!!!", "Recife", null))
                .isInstanceOf(TransferenciaInvalidaException.class);
        assertThatThrownBy(() -> new BrCode(CHAVE, reais("0.00"), "Maria", "Recife", null))
                .isInstanceOf(TransferenciaInvalidaException.class);
    }

    private static String campo(String tag, String valor) {
        return tag + "%02d".formatted(valor.length()) + valor;
    }

    private static String comCrc(String semCrc) {
        return semCrc + Crc16.calcular(semCrc);
    }
}
