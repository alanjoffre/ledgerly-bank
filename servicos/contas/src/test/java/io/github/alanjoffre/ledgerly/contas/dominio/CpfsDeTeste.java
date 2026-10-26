package io.github.alanjoffre.ledgerly.contas.dominio;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Gera CPFs válidos (dígitos verificadores corretos) a partir de números aleatórios, para teste.
 * Nenhum dado pessoal real entra no repositório.
 */
public final class CpfsDeTeste {

    private CpfsDeTeste() {}

    public static String gerar() {
        String base;
        do {
            base = "%09d".formatted(ThreadLocalRandom.current().nextInt(1_000_000_000));
        } while (base.chars().distinct().count() == 1);
        String comPrimeiro = base + Cpf.digitoVerificador(base + "00", 9);
        return comPrimeiro + Cpf.digitoVerificador(comPrimeiro + "0", 10);
    }
}
