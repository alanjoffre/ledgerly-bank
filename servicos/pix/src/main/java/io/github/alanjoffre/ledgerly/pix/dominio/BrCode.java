package io.github.alanjoffre.ledgerly.pix.dominio;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * O QR Code do Pix no padrão <b>BR Code</b> do Banco Central: o texto que vai dentro do QR Code
 * e que o app também oferece como "Pix copia e cola".
 *
 * <p>O formato é o EMV® QRCPS (o mesmo dos pagamentos por QR Code com cartão): uma sequência de
 * campos <b>TLV</b> — <i>tag</i> de 2 dígitos, tamanho de 2 dígitos, valor. Por exemplo,
 * {@code 5303986} é o campo 53 (moeda), com 3 caracteres, valor 986 (código ISO do real).
 *
 * <pre>
 *   00 02 01                       versão do formato
 *   26 .. 00 14 br.gov.bcb.pix     identificador do Pix
 *         01 .. a chave            a chave do recebedor
 *         02 .. a descrição        (opcional)
 *   52 04 0000                     categoria do comerciante (0000 = não informada)
 *   53 03 986                      moeda: real
 *   54 .. 10.50                    valor (opcional: sem ele, quem paga digita)
 *   58 02 BR                       país
 *   59 .. MARIA SOUZA              nome do recebedor (até 25)
 *   60 .. SAO PAULO                cidade do recebedor (até 15)
 *   62 .. 05 .. ***                identificador da transação (*** = QR Code estático, sem id)
 *   63 04 1D3D                     CRC16 de tudo o que vem antes, inclusive "6304"
 * </pre>
 *
 * <p>Este é o QR Code <b>estático</b>: pode ser pago várias vezes (o cartaz no balcão). O dinâmico
 * (uma cobrança, paga uma vez) aponta para uma URL do banco e fica para depois.
 *
 * @param chave         a chave do recebedor
 * @param valor         o valor fixo, ou {@code null} para quem paga digitar
 * @param nomeRecebedor o nome do recebedor (sai em maiúsculas, sem acento, até 25 caracteres)
 * @param cidade        a cidade do recebedor (idem, até 15)
 * @param descricao     texto livre opcional
 */
public record BrCode(ChavePix chave, Dinheiro valor, String nomeRecebedor, String cidade, String descricao) {

    static final String IDENTIFICADOR_PIX = "br.gov.bcb.pix";
    static final int MAXIMO_NOME = 25;
    static final int MAXIMO_CIDADE = 15;
    private static final String SEM_IDENTIFICADOR = "***";

    public BrCode {
        Objects.requireNonNull(chave, "a chave é obrigatória");
        nomeRecebedor = textoEmv(nomeRecebedor, MAXIMO_NOME, "nome do recebedor");
        cidade = textoEmv(cidade, MAXIMO_CIDADE, "cidade");
        descricao = descricao == null || descricao.isBlank() ? null : semAcento(descricao).strip();
        if (valor != null && (!valor.ehPositivo() || !valor.moeda().equals(Dinheiro.REAL))) {
            throw new TransferenciaInvalidaException("O valor do QR Code deve ser positivo e em reais");
        }
    }

    public Optional<Dinheiro> valorFixo() {
        return Optional.ofNullable(valor);
    }

    /** O texto do QR Code: o "copia e cola". */
    public String copiaECola() {
        String contaDoRecebedor = campo("00", IDENTIFICADOR_PIX) + campo("01", chave.valor())
                + (descricao == null ? "" : campo("02", descricao));
        StringBuilder texto = new StringBuilder()
                .append(campo("00", "01"))
                .append(campo("26", contaDoRecebedor))
                .append(campo("52", "0000"))
                .append(campo("53", "986"));
        if (valor != null) {
            texto.append(campo("54", valor.valor().toPlainString()));
        }
        texto.append(campo("58", "BR"))
                .append(campo("59", nomeRecebedor))
                .append(campo("60", cidade))
                .append(campo("62", campo("05", SEM_IDENTIFICADOR)))
                .append("6304");
        return texto.append(Crc16.calcular(texto.toString())).toString();
    }

    /**
     * Lê um "copia e cola" e confere: o CRC, o identificador do Pix e os campos obrigatórios.
     *
     * @throws QrCodeInvalidoException se o texto foi alterado (o CRC não bate) ou não é um BR Code
     */
    public static BrCode ler(String copiaECola) {
        if (copiaECola == null || copiaECola.length() < 8) {
            throw new QrCodeInvalidoException("O texto do QR Code está vazio ou curto demais");
        }
        String texto = copiaECola.strip();
        int inicioCrc = texto.length() - 4;
        if (!texto.startsWith("6304", inicioCrc - 4)) {
            throw new QrCodeInvalidoException("O QR Code não termina com o campo de conferência (CRC)");
        }
        String esperado = Crc16.calcular(texto.substring(0, inicioCrc));
        if (!esperado.equalsIgnoreCase(texto.substring(inicioCrc))) {
            throw new QrCodeInvalidoException("O QR Code foi alterado ou digitado errado: a conferência (CRC) não bate");
        }
        Map<String, String> campos = campos(texto.substring(0, inicioCrc - 4));
        Map<String, String> conta = campos(exigir(campos, "26"));
        if (!IDENTIFICADOR_PIX.equalsIgnoreCase(exigir(conta, "00"))) {
            throw new QrCodeInvalidoException("O QR Code não é de Pix");
        }
        if (!"986".equals(exigir(campos, "53"))) {
            throw new QrCodeInvalidoException("O QR Code não é em reais");
        }
        ChavePix chave;
        try {
            chave = ChavePix.identificar(exigir(conta, "01"));
        } catch (ChaveInvalidaException e) {
            throw new QrCodeInvalidoException("A chave dentro do QR Code é inválida: " + e.getMessage());
        }
        Dinheiro valor = null;
        if (campos.containsKey("54")) {
            try {
                valor = Dinheiro.de(new BigDecimal(campos.get("54")), Dinheiro.REAL);
            } catch (RuntimeException e) {
                throw new QrCodeInvalidoException("O valor do QR Code é inválido: " + campos.get("54"));
            }
        }
        try {
            return new BrCode(chave, valor, exigir(campos, "59"), exigir(campos, "60"), conta.get("02"));
        } catch (RegraDeNegocioException e) {
            throw new QrCodeInvalidoException(e.getMessage());
        }
    }

    private static String campo(String tag, String valor) {
        if (valor.length() > 99) {
            throw new TransferenciaInvalidaException("O campo %s do QR Code passa de 99 caracteres".formatted(tag));
        }
        return tag + "%02d".formatted(valor.length()) + valor;
    }

    private static Map<String, String> campos(String texto) {
        Map<String, String> campos = new LinkedHashMap<>();
        int i = 0;
        while (i < texto.length()) {
            if (i + 4 > texto.length()) {
                throw new QrCodeInvalidoException("O QR Code termina no meio de um campo");
            }
            String tag = texto.substring(i, i + 2);
            int tamanho;
            try {
                tamanho = Integer.parseInt(texto.substring(i + 2, i + 4));
            } catch (NumberFormatException e) {
                throw new QrCodeInvalidoException("Tamanho de campo inválido no QR Code: " + texto.substring(i + 2, i + 4));
            }
            if (i + 4 + tamanho > texto.length()) {
                throw new QrCodeInvalidoException("O campo %s do QR Code passa do fim do texto".formatted(tag));
            }
            campos.put(tag, texto.substring(i + 4, i + 4 + tamanho));
            i += 4 + tamanho;
        }
        return campos;
    }

    private static String exigir(Map<String, String> campos, String tag) {
        String valor = campos.get(tag);
        if (valor == null || valor.isEmpty()) {
            throw new QrCodeInvalidoException("Falta o campo %s no QR Code".formatted(tag));
        }
        return valor;
    }

    /** O BR Code só aceita letras sem acento: "São Paulo" vira "SAO PAULO". */
    private static String textoEmv(String texto, int maximo, String nome) {
        if (texto == null || texto.isBlank()) {
            throw new TransferenciaInvalidaException("O campo '%s' do QR Code é obrigatório".formatted(nome));
        }
        String limpo = semAcento(texto).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", "").strip();
        if (limpo.isEmpty()) {
            throw new TransferenciaInvalidaException("O campo '%s' do QR Code ficou vazio".formatted(nome));
        }
        return limpo.length() > maximo ? limpo.substring(0, maximo).strip() : limpo;
    }

    private static String semAcento(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("[^\\x20-\\x7E]", "");
    }
}
