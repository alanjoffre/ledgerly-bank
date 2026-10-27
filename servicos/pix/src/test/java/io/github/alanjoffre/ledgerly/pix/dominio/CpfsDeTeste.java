package io.github.alanjoffre.ledgerly.pix.dominio;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Gera CPFs válidos (dígitos verificadores certos) a cada execução — nunca um CPF fixo no código,
 * que poderia ser o de uma pessoa real.
 */
public final class CpfsDeTeste {

    private CpfsDeTeste() {}

    public static String gerar() {
        while (true) {
            StringBuilder base = new StringBuilder();
            for (int i = 0; i < 9; i++) {
                base.append(ThreadLocalRandom.current().nextInt(10));
            }
            String com10 = base.toString() + digito(base.toString(), 9);
            String cpf = com10 + digito(com10, 10);
            if (cpf.chars().distinct().count() > 1) {
                return cpf;
            }
        }
    }

    private static int digito(String digitos, int posicao) {
        int soma = 0;
        for (int i = 0; i < posicao; i++) {
            soma += (digitos.charAt(i) - '0') * (posicao + 1 - i);
        }
        int resto = (soma * 10) % 11;
        return resto == 10 ? 0 : resto;
    }

    /** O mesmo CPF com o último dígito trocado: formato certo, conta errada. */
    public static String comDigitoErrado(String cpf) {
        char ultimo = cpf.charAt(10);
        return cpf.substring(0, 10) + (char) ('0' + ((ultimo - '0' + 1) % 10));
    }
}
