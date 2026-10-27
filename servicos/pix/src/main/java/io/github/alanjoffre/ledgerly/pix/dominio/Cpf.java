package io.github.alanjoffre.ledgerly.pix.dominio;

/**
 * Conferência dos dígitos verificadores do CPF, para validar a chave do tipo CPF.
 *
 * <p>O serviço de contas tem a sua própria classe {@code Cpf}, mais completa. Aqui fica só a conta
 * dos dígitos, de propósito: cada serviço tem o seu domínio (<i>bounded context</i>), e
 * compartilhar uma classe de domínio entre dois serviços os amarraria um ao outro.
 */
final class Cpf {

    private Cpf() {}

    /** @param digitos exatamente 11 dígitos */
    static boolean digitosConferem(String digitos) {
        if (digitos.chars().distinct().count() == 1) {
            return false; // 000.000.000-00, 111.111.111-11...: a conta fecha, mas não são CPFs
        }
        return digito(digitos, 9) == digitos.charAt(9) - '0' && digito(digitos, 10) == digitos.charAt(10) - '0';
    }

    private static int digito(String digitos, int posicao) {
        int soma = 0;
        for (int i = 0; i < posicao; i++) {
            soma += (digitos.charAt(i) - '0') * (posicao + 1 - i);
        }
        int resto = (soma * 10) % 11;
        return resto == 10 ? 0 : resto;
    }
}
