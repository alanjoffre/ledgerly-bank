package io.github.alanjoffre.ledgerly.contas.dominio;

import java.util.Objects;

/**
 * CPF validado: 11 dígitos, com os dois dígitos verificadores corretos.
 *
 * <p><b>Dígitos verificadores:</b> os dois últimos números do CPF são calculados a partir dos nove
 * primeiros (módulo 11). Um erro de digitação quase sempre quebra a conta e é pego aqui, antes de
 * ir para o banco.
 *
 * <p><b>LGPD:</b> o CPF é dado pessoal. O {@link #toString()} devolve a versão mascarada, para que
 * ele nunca vá inteiro para um log por descuido. Para o número completo, use {@link #numero()}.
 *
 * @param numero só os 11 dígitos, sem pontos nem traço
 */
public record Cpf(String numero) {

    public Cpf {
        Objects.requireNonNull(numero, "CPF é obrigatório");
        if (!numero.matches("\\d{11}")) {
            throw new CpfInvalidoException("O CPF deve ter 11 dígitos");
        }
        if (numero.chars().distinct().count() == 1) {
            // 000.000.000-00, 111.111.111-11...: passam na conta, mas não são CPFs válidos.
            throw new CpfInvalidoException("CPF com todos os dígitos iguais não é válido");
        }
        if (digitoVerificador(numero, 9) != numero.charAt(9) - '0'
                || digitoVerificador(numero, 10) != numero.charAt(10) - '0') {
            throw new CpfInvalidoException("Os dígitos verificadores do CPF não conferem");
        }
    }

    /** Aceita com ou sem máscara: {@code "ddd.ddd.ddd-dd"} ou {@code "ddddddddddd"}. */
    public static Cpf de(String texto) {
        Objects.requireNonNull(texto, "CPF é obrigatório");
        if (!texto.matches("\\d{11}|\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}")) {
            throw new CpfInvalidoException("Formato de CPF inválido; use 000.000.000-00 ou só os dígitos");
        }
        return new Cpf(texto.replaceAll("\\D", ""));
    }

    /**
     * Calcula um dígito verificador. Para o 1º dígito ({@code tamanho} = 9), multiplica os 9
     * primeiros por 10, 9, ..., 2; para o 2º ({@code tamanho} = 10), os 10 primeiros por 11, ..., 2.
     * O dígito é {@code 11 - (soma % 11)}, e vira 0 quando dá 10 ou 11.
     */
    static int digitoVerificador(String digitos, int tamanho) {
        int soma = 0;
        for (int i = 0; i < tamanho; i++) {
            soma += (digitos.charAt(i) - '0') * (tamanho + 1 - i);
        }
        int resto = 11 - (soma % 11);
        return resto >= 10 ? 0 : resto;
    }

    /** {@code ddd.ddd.ddd-dd} */
    public String formatado() {
        return "%s.%s.%s-%s".formatted(
                numero.substring(0, 3), numero.substring(3, 6), numero.substring(6, 9), numero.substring(9));
    }

    /** {@code ***.ddd.ddd-**} — o formato usado em respostas e logs. */
    public String mascarado() {
        return "***.%s.%s-**".formatted(numero.substring(3, 6), numero.substring(6, 9));
    }

    @Override
    public String toString() {
        return mascarado();
    }
}
