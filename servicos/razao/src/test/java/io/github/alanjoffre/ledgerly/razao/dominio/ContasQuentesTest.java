package io.github.alanjoffre.ledgerly.razao.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Contas quentes divididas em sub-contas")
class ContasQuentesTest {

    @Test
    @DisplayName("a sub-conta 0 é a própria conta antiga; o id diz a sub-conta e a conta-mãe")
    void ids() {
        assertThat(ContasQuentes.subconta(ContaRazao.CAIXA, 0)).isEqualTo(ContaRazao.CAIXA);
        assertThat(ContasQuentes.subconta(ContaRazao.CAIXA, 3))
                .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000301"));
        assertThat(ContasQuentes.subconta(ContaRazao.LIQUIDACAO_PIX, 15))
                .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000f02"));
        assertThat(ContasQuentes.contaMae(UUID.fromString("00000000-0000-0000-0000-000000000f02")))
                .isEqualTo(ContaRazao.LIQUIDACAO_PIX);
        assertThat(ContasQuentes.todasAsSubcontas(ContaRazao.CAIXA)).hasSize(16).allMatch(ContaRazao::ehInterna);
    }

    @Test
    @DisplayName("conta de cliente não é interna e volta igual em contaMae")
    void contaDeCliente() {
        UUID cliente = UUID.randomUUID();
        assertThat(ContaRazao.ehInterna(cliente)).isFalse();
        assertThat(ContasQuentes.contaMae(cliente)).isEqualTo(cliente);
    }

    @Test
    @DisplayName("o mesmo cliente cai sempre na mesma sub-conta; clientes diferentes se espalham por todas")
    void espalhamento() {
        var quentes = new ContasQuentes(16);
        UUID cliente = UUID.randomUUID();
        assertThat(quentes.caixaPara(cliente)).isEqualTo(quentes.caixaPara(cliente));

        Set<UUID> usadas = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            usadas.add(quentes.caixaPara(UUID.randomUUID()));
        }
        assertThat(usadas).hasSize(16).allMatch(id -> ContasQuentes.contaMae(id).equals(ContaRazao.CAIXA));
    }

    @Test
    @DisplayName("com 1 sub-conta, tudo vai para a conta antiga — o comportamento de antes da Fase 3")
    void umaSubconta() {
        var quentes = new ContasQuentes(1);
        assertThat(quentes.caixaPara(UUID.randomUUID())).isEqualTo(ContaRazao.CAIXA);
        assertThat(quentes.liquidacaoPara(UUID.randomUUID())).isEqualTo(ContaRazao.LIQUIDACAO_PIX);
        assertThat(quentes.subcontas()).isEqualTo(1);
    }

    @Test
    @DisplayName("fora de 1 a 16: recusado")
    void limites() {
        assertThatThrownBy(() -> new ContasQuentes(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContasQuentes(17)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContasQuentes.subconta(ContaRazao.CAIXA, 16)).isInstanceOf(IllegalArgumentException.class);
    }
}
