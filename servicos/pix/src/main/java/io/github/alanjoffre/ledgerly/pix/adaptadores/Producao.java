package io.github.alanjoffre.ledgerly.pix.adaptadores;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.time.Clock;

/** Objetos que o Quarkus injeta e que não são classes nossas. */
@ApplicationScoped
class Producao {

    /** O relógio vira dependência: nos testes dá para controlar a hora. */
    @Produces
    @ApplicationScoped
    Clock relogio() {
        return Clock.systemUTC();
    }
}
