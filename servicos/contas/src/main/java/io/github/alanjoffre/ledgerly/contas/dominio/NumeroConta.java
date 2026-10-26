package io.github.alanjoffre.ledgerly.contas.dominio;

/**
 * Agência, número e dígito verificador da conta, como aparecem para o cliente: {@code 0001 / 100001-9}.
 *
 * <p>O dígito usa módulo 11 — o mesmo princípio do CPF — para pegar erro de digitação quando
 * alguém informa a conta de destino.
 *
 * @param agencia 4 dígitos
 * @param numero  número sequencial, gerado pelo banco de dados
 * @param digito  dígito verificador
 */
public record NumeroConta(String agencia, int numero, int digito) {

    /** O Ledgerly é um banco digital: agência única. */
    public static final String AGENCIA_UNICA = "0001";

    public NumeroConta {
        if (agencia == null || !agencia.matches("\\d{4}")) {
            throw new DadoInvalidoException("A agência deve ter 4 dígitos");
        }
        if (numero <= 0) {
            throw new DadoInvalidoException("O número da conta deve ser positivo");
        }
        if (digito != calcularDigito(numero)) {
            throw new DadoInvalidoException("O dígito verificador da conta não confere");
        }
    }

    public static NumeroConta novo(int numero) {
        return new NumeroConta(AGENCIA_UNICA, numero, calcularDigito(numero));
    }

    /** Módulo 11 com pesos 2, 3, 4... da direita para a esquerda; resto 10 ou 11 vira 0. */
    static int calcularDigito(int numero) {
        int soma = 0;
        int peso = 2;
        for (int restante = numero; restante > 0; restante /= 10) {
            soma += (restante % 10) * peso++;
        }
        int digito = 11 - (soma % 11);
        return digito >= 10 ? 0 : digito;
    }

    @Override
    public String toString() {
        return "%s / %d-%d".formatted(agencia, numero, digito);
    }
}
