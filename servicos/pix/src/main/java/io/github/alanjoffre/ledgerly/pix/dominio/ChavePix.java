package io.github.alanjoffre.ledgerly.pix.dominio;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Uma chave Pix: o "apelido" que aponta para uma conta.
 *
 * <p><b>Padrão:</b> <i>value object</i> (DDD) — não tem identidade própria, vale pelo conteúdo, e só
 * existe válida: o construtor normaliza ({@code "123.456.789-09"} vira {@code "12345678909"}) e
 * recusa o que não segue o formato do tipo.
 *
 * @param tipo  o tipo da chave
 * @param valor o valor já normalizado, como fica guardado
 */
public record ChavePix(TipoChave tipo, String valor) {

    /** Limite do DICT para e-mail. */
    public static final int TAMANHO_MAXIMO_EMAIL = 77;

    private static final Pattern EMAIL = Pattern.compile("^[a-z0-9._%+-]+@[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}$");
    private static final Pattern TELEFONE = Pattern.compile("^\\+55[1-9][0-9]9?[0-9]{8}$");
    private static final Pattern UUID_V4 =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    public ChavePix {
        Objects.requireNonNull(tipo, "o tipo da chave é obrigatório");
        if (valor == null || valor.isBlank()) {
            throw new ChaveInvalidaException("O valor da chave é obrigatório");
        }
        valor = normalizar(tipo, valor);
    }

    /** Gera uma chave aleatória nova (UUID versão 4). */
    public static ChavePix aleatoria() {
        return new ChavePix(TipoChave.ALEATORIA, UUID.randomUUID().toString());
    }

    /**
     * Descobre o tipo pelo formato — é o que o app faz quando o cliente digita a chave de quem vai
     * receber, sem dizer o tipo.
     */
    public static ChavePix identificar(String bruto) {
        if (bruto == null || bruto.isBlank()) {
            throw new ChaveInvalidaException("A chave é obrigatória");
        }
        String texto = bruto.strip();
        if (texto.startsWith("+")) {
            return new ChavePix(TipoChave.TELEFONE, texto);
        }
        if (texto.contains("@")) {
            return new ChavePix(TipoChave.EMAIL, texto);
        }
        if (UUID_V4.matcher(texto.toLowerCase(Locale.ROOT)).matches()) {
            return new ChavePix(TipoChave.ALEATORIA, texto);
        }
        return new ChavePix(TipoChave.CPF, texto);
    }

    /**
     * A chave para mostrar a outra pessoa, sem expor o dado inteiro: {@code ***.456.789-**},
     * {@code m***@exemplo.com}, {@code +55 (11) *****-4321}. A aleatória não é dado pessoal e sai
     * inteira.
     */
    public String mascarada() {
        return switch (tipo) {
            case CPF -> "***.%s.%s-**".formatted(valor.substring(3, 6), valor.substring(6, 9));
            case EMAIL -> valor.charAt(0) + "***" + valor.substring(valor.indexOf('@'));
            case TELEFONE -> "+55 (%s) *****-%s".formatted(valor.substring(3, 5), valor.substring(valor.length() - 4));
            case ALEATORIA -> valor;
        };
    }

    @Override
    public String toString() {
        return tipo + ":" + mascarada();
    }

    private static String normalizar(TipoChave tipo, String bruto) {
        String texto = bruto.strip();
        return switch (tipo) {
            case CPF -> {
                String digitos = texto.replaceAll("[.\\-]", "");
                if (!digitos.matches("\\d{11}") || !Cpf.digitosConferem(digitos)) {
                    throw new ChaveInvalidaException("CPF inválido");
                }
                yield digitos;
            }
            case EMAIL -> {
                String email = texto.toLowerCase(Locale.ROOT);
                if (email.length() > TAMANHO_MAXIMO_EMAIL || !EMAIL.matcher(email).matches()) {
                    throw new ChaveInvalidaException("E-mail inválido (até %d caracteres)".formatted(TAMANHO_MAXIMO_EMAIL));
                }
                yield email;
            }
            case TELEFONE -> {
                String telefone = texto.replaceAll("[\\s()\\-]", "");
                if (!TELEFONE.matcher(telefone).matches()) {
                    throw new ChaveInvalidaException("Telefone inválido: use o formato +55DDNNNNNNNNN");
                }
                yield telefone;
            }
            case ALEATORIA -> {
                String uuid = texto.toLowerCase(Locale.ROOT);
                if (!UUID_V4.matcher(uuid).matches()) {
                    throw new ChaveInvalidaException("Chave aleatória inválida: deve ser um UUID versão 4");
                }
                yield uuid;
            }
        };
    }
}
