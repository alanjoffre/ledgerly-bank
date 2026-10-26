package io.github.alanjoffre.ledgerly.contas.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Conta: máquina de estados")
class ContaTest {

    private static final Instant AGORA = Instant.parse("2026-10-26T12:00:00.123456789Z");

    private static Conta nova() {
        return Conta.abrir(UUID.randomUUID(), NumeroConta.novo(100001), AGORA);
    }

    @Test
    @DisplayName("nasce ativa, sem data de encerramento, versão 0, hora em microssegundos")
    void nasceAtiva() {
        Conta conta = nova();
        assertThat(conta.situacao()).isEqualTo(SituacaoConta.ATIVA);
        assertThat(conta.encerradaEm()).isNull();
        assertThat(conta.versao()).isZero();
        assertThat(conta.abertaEm()).isEqualTo(Instant.parse("2026-10-26T12:00:00.123456Z"));
    }

    @Test
    @DisplayName("ATIVA → BLOQUEADA → ATIVA → ENCERRADA")
    void caminhoFeliz() {
        Conta conta = nova();
        conta.bloquear();
        assertThat(conta.situacao()).isEqualTo(SituacaoConta.BLOQUEADA);
        conta.desbloquear();
        assertThat(conta.situacao()).isEqualTo(SituacaoConta.ATIVA);
        conta.encerrar(AGORA);
        assertThat(conta.situacao()).isEqualTo(SituacaoConta.ENCERRADA);
        assertThat(conta.encerradaEm()).isNotNull();
    }

    @Test
    @DisplayName("transições proibidas")
    void transicoesProibidas() {
        Conta ativa = nova();
        assertThatThrownBy(ativa::desbloquear).isInstanceOf(TransicaoInvalidaException.class);

        Conta bloqueada = nova();
        bloqueada.bloquear();
        assertThatThrownBy(bloqueada::bloquear).isInstanceOf(TransicaoInvalidaException.class);
        assertThatThrownBy(() -> bloqueada.encerrar(AGORA))
                .as("conta bloqueada não encerra: o motivo do bloqueio precisa ser resolvido antes")
                .isInstanceOf(TransicaoInvalidaException.class);

        Conta encerrada = nova();
        encerrada.encerrar(AGORA);
        assertThatThrownBy(encerrada::bloquear).isInstanceOf(TransicaoInvalidaException.class);
        assertThatThrownBy(encerrada::desbloquear).isInstanceOf(TransicaoInvalidaException.class);
        assertThatThrownBy(() -> encerrada.encerrar(AGORA))
                .isInstanceOf(TransicaoInvalidaException.class)
                .hasMessageContaining("está ENCERRADA");
    }

    @Test
    @DisplayName("reconstituir preserva tudo, inclusive a versão")
    void reconstituir() {
        UUID id = UUID.randomUUID();
        Conta conta = Conta.reconstituir(id, UUID.randomUUID(), NumeroConta.novo(7), SituacaoConta.ENCERRADA, AGORA, AGORA, 3);
        assertThat(conta.id()).isEqualTo(id);
        assertThat(conta.versao()).isEqualTo(3);
        assertThat(conta.encerradaEm()).isEqualTo(conta.abertaEm());
    }
}
