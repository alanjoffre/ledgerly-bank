package io.github.alanjoffre.ledgerly.extrato.aplicacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos.LancamentoRegistrado;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos.PartidaRegistrada;
import io.github.alanjoffre.ledgerly.extrato.dominio.ContaNaoEncontradaException;
import io.github.alanjoffre.ledgerly.extrato.dominio.Movimentacao;
import io.github.alanjoffre.ledgerly.extrato.dominio.TipoOperacao;
import io.github.alanjoffre.ledgerly.extrato.dominio.TipoPartida;
import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Consulta do extrato")
class ConsultaDoExtratoTest {

    private final RepositoriosEmMemoria memoria = new RepositoriosEmMemoria();
    private final ProjecaoDoExtrato projecao = new ProjecaoDoExtrato(memoria, memoria);
    private final ConsultaDoExtrato consulta = new ConsultaDoExtrato(memoria, memoria);
    private final UUID conta = UUID.randomUUID();

    private void depositos(int quantos) {
        for (int i = 1; i <= quantos; i++) {
            projecao.projetar(new LancamentoRegistrado(UUID.randomUUID(), TipoOperacao.DEPOSITO, null, Instant.EPOCH,
                    List.of(new PartidaRegistrada(i, conta, false, TipoPartida.CREDITO, Dinheiro.reais("1.00"), Dinheiro.reais(i + ".00")))));
        }
    }

    @Test
    @DisplayName("percorrer as páginas pelo cursor devolve cada linha uma vez, da mais nova para a mais antiga")
    void paginasCompletas() {
        depositos(7);
        Long cursor = null;
        List<Long> vistas = new ArrayList<>();
        do {
            PaginaDoExtrato pagina = consulta.extrato(conta, 3, cursor);
            pagina.itens().forEach(m -> vistas.add(m.razao().sequencia()));
            cursor = pagina.proximaPagina();
        } while (cursor != null);
        assertThat(vistas).containsExactly(7L, 6L, 5L, 4L, 3L, 2L, 1L);
    }

    @Test
    @DisplayName("página exata: sem próxima página quando não há mais linhas")
    void paginaExata() {
        depositos(3);
        PaginaDoExtrato pagina = consulta.extrato(conta, 3, null);
        assertThat(pagina.itens()).extracting(Movimentacao::descricao).containsOnly("Depósito");
        assertThat(pagina.proximaPagina()).isNull();
        assertThat(pagina.saldo().valor()).isEqualTo(Dinheiro.reais("3.00"));
    }

    @Test
    @DisplayName("limite de 1 a 500; antesDe positivo")
    void faixas() {
        depositos(1);
        assertThat(consulta.extrato(conta, 1, null).itens()).hasSize(1);
        assertThat(consulta.extrato(conta, 500, null).itens()).hasSize(1);
        assertThatIllegalArgumentException().isThrownBy(() -> consulta.extrato(conta, 0, null));
        assertThatIllegalArgumentException().isThrownBy(() -> consulta.extrato(conta, 501, null));
        assertThatIllegalArgumentException().isThrownBy(() -> consulta.extrato(conta, 10, 0L));
    }

    @Test
    @DisplayName("conta sem nada: 'não encontrada'; página além do fim de conta conhecida: vazia")
    void contaDesconhecida() {
        assertThatThrownBy(() -> consulta.extrato(conta, 10, null)).isInstanceOf(ContaNaoEncontradaException.class);
        assertThatThrownBy(() -> consulta.saldo(conta)).isInstanceOf(ContaNaoEncontradaException.class);
        depositos(1);
        assertThat(consulta.extrato(conta, 10, 1L).itens()).isEmpty();
        assertThat(consulta.extrato(UUID.randomUUID(), 10, 5L).itens()).isEmpty();
    }
}
