package io.github.alanjoffre.ledgerly.pix.dominio;

import java.time.Duration;

/**
 * Quanto esperar antes de repetir um passo que ficou sem resposta: <b>espera exponencial</b>
 * (<i>exponential backoff</i>) — 1 s, 2 s, 4 s, 8 s... até o teto de 1 minuto.
 *
 * <p>Por que dobrar: se o razão caiu por excesso de carga, repetir na mesma hora só piora. Esperar
 * cada vez mais dá tempo para ele voltar. Não há limite de tentativas: o dinheiro já saiu do
 * pagador e precisa chegar a algum lugar. Depois de {@link #ALERTA} tentativas, o serviço avisa no
 * log que alguém precisa olhar.
 */
public final class NovasTentativas {

    public static final Duration PRIMEIRA = Duration.ofSeconds(1);
    public static final Duration TETO = Duration.ofMinutes(1);
    public static final int ALERTA = 10;

    private NovasTentativas() {}

    /** @param tentativas quantas tentativas já falharam (1 ou mais) */
    public static Duration espera(int tentativas) {
        if (tentativas < 1) {
            throw new IllegalArgumentException("tentativas deve ser 1 ou mais");
        }
        if (tentativas > 7) {
            return TETO; // 2^6 s = 64 s já passa do teto, e assim não estoura o long
        }
        Duration espera = PRIMEIRA.multipliedBy(1L << (tentativas - 1));
        return espera.compareTo(TETO) > 0 ? TETO : espera;
    }
}
