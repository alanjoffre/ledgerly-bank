package io.github.alanjoffre.ledgerly.contas.dominio;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/**
 * Um fato que aconteceu com uma conta e que outros serviços precisam saber — hoje, o razão.
 *
 * <p><b>Evento de domínio</b> (<i>domain event</i>): escrito no passado ({@code ContaAberta}, não
 * "abrir conta"), porque descreve algo que já aconteceu e não pode ser recusado. Quem publica não
 * sabe quem vai ouvir. O formato no Kafka está em {@code docs/arquitetura/eventos.md}.
 *
 * <p><b>Interface selada</b> ({@code sealed}): só os quatro tipos abaixo existem. Um {@code switch}
 * sobre o evento que esqueça um deles não compila.
 */
public sealed interface EventoDeConta {

    UUID contaId();

    /** Quando aconteceu, em microssegundos — a precisão que o PostgreSQL guarda. */
    Instant ocorridoEm();

    record ContaAberta(UUID contaId, UUID clienteId, Currency moeda, Instant ocorridoEm) implements EventoDeConta {
        public ContaAberta {
            Objects.requireNonNull(contaId, "contaId é obrigatório");
            Objects.requireNonNull(clienteId, "clienteId é obrigatório");
            Objects.requireNonNull(moeda, "moeda é obrigatória");
            ocorridoEm = micros(ocorridoEm);
        }
    }

    record ContaBloqueada(UUID contaId, Instant ocorridoEm) implements EventoDeConta {
        public ContaBloqueada {
            Objects.requireNonNull(contaId, "contaId é obrigatório");
            ocorridoEm = micros(ocorridoEm);
        }
    }

    record ContaDesbloqueada(UUID contaId, Instant ocorridoEm) implements EventoDeConta {
        public ContaDesbloqueada {
            Objects.requireNonNull(contaId, "contaId é obrigatório");
            ocorridoEm = micros(ocorridoEm);
        }
    }

    record ContaEncerrada(UUID contaId, Instant ocorridoEm) implements EventoDeConta {
        public ContaEncerrada {
            Objects.requireNonNull(contaId, "contaId é obrigatório");
            ocorridoEm = micros(ocorridoEm);
        }
    }

    private static Instant micros(Instant instante) {
        return Objects.requireNonNull(instante, "ocorridoEm é obrigatório").truncatedTo(ChronoUnit.MICROS);
    }
}
