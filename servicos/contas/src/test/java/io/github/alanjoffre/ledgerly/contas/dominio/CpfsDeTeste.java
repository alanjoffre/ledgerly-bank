package io.github.alanjoffre.ledgerly.contas.dominio;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Gera CPFs válidos (dígitos verificadores corretos) a partir de números aleatórios, para teste.
 * Nenhum dado pessoal real entra no repositório.
 *
 * <p>Faz a conta por outra fórmula, sem chamar {@link Cpf}: se usasse a mesma função que os testes
 * conferem, um erro nela passaria despercebido (o teste de mutação achou isso, ADR-0044).
 */
public final class CpfsDeTeste {

    private CpfsDeTeste() {}

    public static String gerar() {
        String base;
        do {
            base = "%09d".formatted(ThreadLocalRandom.current().nextInt(1_000_000_000));
        } while (base.chars().distinct().count() == 1);
        String comPrimeiro = base + digito(base);
        return comPrimeiro + digito(comPrimeiro);
    }

    /** Soma com pesos crescentes a partir do último dígito; o verificador é {@code soma * 10 % 11 % 10}. */
    private static int digito(String digitos) {
        int soma = 0;
        for (int i = 0; i < digitos.length(); i++) {
            soma += (digitos.charAt(digitos.length() - 1 - i) - '0') * (i + 2);
        }
        return soma * 10 % 11 % 10;
    }
}
