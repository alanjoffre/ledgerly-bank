package io.github.alanjoffre.ledgerly.pix.adaptadores.entrada.web;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.Map;

/**
 * Desenha o QR Code como SVG (imagem vetorial, em texto): cada módulo preto vira um quadrado de
 * 1×1. Sem AWT — o que funciona também na imagem nativa do GraalVM.
 */
final class QrCodeSvg {

    private QrCodeSvg() {}

    static String desenhar(String texto) {
        BitMatrix matriz;
        try {
            // Correção de erro M: o QR Code ainda é lido com até ~15% dele danificado ou sujo.
            matriz = new QRCodeWriter().encode(texto, BarcodeFormat.QR_CODE, 0, 0, Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN, 4,
                    EncodeHintType.CHARACTER_SET, "UTF-8"));
        } catch (WriterException e) {
            throw new IllegalStateException("Não foi possível desenhar o QR Code", e);
        }
        int largura = matriz.getWidth();
        int altura = matriz.getHeight();
        StringBuilder caminho = new StringBuilder();
        for (int y = 0; y < altura; y++) {
            for (int x = 0; x < largura; x++) {
                if (matriz.get(x, y)) {
                    caminho.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
                }
            }
        }
        return """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" shape-rendering="crispEdges">\
                <rect width="100%%" height="100%%" fill="#fff"/><path fill="#000" d="%s"/></svg>"""
                .formatted(largura, altura, caminho);
    }
}
