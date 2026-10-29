package io.github.alanjoffre.ledgerly.seguranca;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Usuario: quem fez o pedido")
class UsuarioTest {

    private static final UUID CLIENTE = UUID.randomUUID();

    @Test
    @DisplayName("é dono só do próprio cliente_id, e só com o papel cliente")
    void dono() {
        var ana = new Usuario("s", "ana", Optional.of(CLIENTE), Set.of(Papeis.CLIENTE));
        assertThat(ana.ehDono(CLIENTE)).isTrue();
        assertThat(ana.ehDono(UUID.randomUUID())).isFalse();

        var semPapel = new Usuario("s", "x", Optional.of(CLIENTE), Set.of());
        assertThat(semPapel.ehDono(CLIENTE)).as("cliente_id sem o papel cliente").isFalse();

        var semVinculo = new Usuario("s", "x", Optional.empty(), Set.of(Papeis.CLIENTE));
        assertThat(semVinculo.ehDono(CLIENTE)).isFalse();
    }

    @Test
    @DisplayName("papéis: serviço e analista")
    void papeis() {
        var servico = new Usuario("s", "pix", Optional.empty(), Set.of(Papeis.SERVICO));
        assertThat(servico.ehServico()).isTrue();
        assertThat(servico.ehAnalista()).isFalse();
        var carla = new Usuario("s", "carla", Optional.empty(), Set.of(Papeis.ANALISTA));
        assertThat(carla.ehAnalista()).isTrue();
        assertThat(carla.tem(Papeis.CLIENTE)).isFalse();
    }
}
