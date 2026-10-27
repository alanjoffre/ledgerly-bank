package io.github.alanjoffre.ledgerly.pix.dominio;

import java.nio.charset.StandardCharsets;

/**
 * CRC16-CCITT (polinômio 0x1021, valor inicial 0xFFFF): o dígito de conferência do BR Code.
 *
 * <p>Um <b>CRC</b> (<i>cyclic redundancy check</i>) é um número calculado a partir do texto inteiro.
 * Se um caractere muda — alguém editou o valor, ou a câmera leu errado —, o número muda, e o app
 * recusa o QR Code em vez de pagar a quantia errada.
 */
final class Crc16 {

    private Crc16() {}

    /** Os 4 dígitos hexadecimais, em maiúsculas. */
    static String calcular(String texto) {
        int crc = 0xFFFF;
        for (byte b : texto.getBytes(StandardCharsets.UTF_8)) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xFFFF;
            }
        }
        return "%04X".formatted(crc);
    }
}
