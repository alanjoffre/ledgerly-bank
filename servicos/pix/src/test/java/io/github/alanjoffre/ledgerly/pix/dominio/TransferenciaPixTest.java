package io.github.alanjoffre.ledgerly.pix.dominio;

import static io.github.alanjoffre.ledgerly.nucleo.Dinheiro.reais;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Transferência Pix: a Saga como máquina de estados")
class TransferenciaPixTest {

    private static final Instant AGORA = Instant.parse("2026-10-27T12:00:00Z");
    private static final Participante JOAO = new Participante(UUID.randomUUID(), "João");
    private static final Participante MARIA = new Participante(UUID.randomUUID(), "Maria");
    private static final ChavePix CHAVE = new ChavePix(TipoChave.EMAIL, "maria@exemplo.com");

    private static TransferenciaPix nova() {
        return TransferenciaPix.criar("chave-1", JOAO, MARIA, CHAVE, reais("100.00"), "Aluguel", AGORA);
    }

    private static ResultadoDoPasso feito() {
        return new ResultadoDoPasso.Feito(UUID.randomUUID());
    }

    @Nested
    @DisplayName("caminhos da Saga")
    class Caminhos {

        @Test
        @DisplayName("feliz: CRIADA → DEBITADA → CONCLUIDA")
        void feliz() {
            TransferenciaPix t = nova();
            assertThat(t.proximoPasso()).contains(Passo.DEBITAR);
            t.aplicar(Passo.DEBITAR, feito(), AGORA);
            assertThat(t.situacao()).isEqualTo(SituacaoTransferencia.DEBITADA);
            assertThat(t.proximoPasso()).contains(Passo.CREDITAR);
            t.aplicar(Passo.CREDITAR, feito(), AGORA.plusSeconds(1));

            assertThat(t.situacao()).isEqualTo(SituacaoTransferencia.CONCLUIDA);
            assertThat(t.proximoPasso()).isEmpty();
            assertThat(t.lancamentoEnvioId()).isNotNull();
            assertThat(t.lancamentoRecebimentoId()).isNotNull();
            assertThat(t.finalizadaEm()).isEqualTo(AGORA.plusSeconds(1));
        }

        @Test
        @DisplayName("débito recusado: REJEITADA, sem compensação (nenhum dinheiro saiu)")
        void debitoRecusado() {
            TransferenciaPix t = nova();
            t.aplicar(Passo.DEBITAR, new ResultadoDoPasso.Recusado("saldo-insuficiente", "Saldo insuficiente"), AGORA);
            assertThat(t.situacao()).isEqualTo(SituacaoTransferencia.REJEITADA);
            assertThat(t.motivo()).isEqualTo("Saldo insuficiente");
            assertThat(t.proximoPasso()).isEmpty();
        }

        @Test
        @DisplayName("crédito recusado: ESTORNANDO → ESTORNADA (a compensação)")
        void creditoRecusado() {
            TransferenciaPix t = nova();
            t.aplicar(Passo.DEBITAR, feito(), AGORA);
            t.aplicar(Passo.CREDITAR, new ResultadoDoPasso.Recusado("conta-inativa", "conta de destino encerrada"), AGORA);
            assertThat(t.situacao()).isEqualTo(SituacaoTransferencia.ESTORNANDO);
            assertThat(t.proximoPasso()).contains(Passo.ESTORNAR);
            t.aplicar(Passo.ESTORNAR, feito(), AGORA);

            assertThat(t.situacao()).isEqualTo(SituacaoTransferencia.ESTORNADA);
            assertThat(t.lancamentoEstornoId()).isNotNull();
            assertThat(t.motivo()).isEqualTo("conta de destino encerrada");
        }

        @Test
        @DisplayName("estorno recusado: EM_ANALISE — o dinheiro ficou na liquidação e uma pessoa decide")
        void estornoRecusado() {
            TransferenciaPix t = nova();
            t.aplicar(Passo.DEBITAR, feito(), AGORA);
            t.aplicar(Passo.CREDITAR, new ResultadoDoPasso.Recusado("conta-inativa", "destino encerrado"), AGORA);
            t.aplicar(Passo.ESTORNAR, new ResultadoDoPasso.Recusado("conta-inativa", "pagador encerrado"), AGORA);
            assertThat(t.situacao()).isEqualTo(SituacaoTransferencia.EM_ANALISE);
            assertThat(t.motivo()).isEqualTo("destino encerrado; estorno recusado: pagador encerrado");
            assertThat(t.situacao().ehFinal()).isTrue();
        }
    }

    @Nested
    @DisplayName("sem resposta não é recusa")
    class SemResposta {

        @Test
        @DisplayName("conta nova que o razão ainda não conhece: repete o crédito em vez de estornar")
        void contaNovaAindaNaoConhecida() {
            var t = nova();
            t.aplicar(Passo.DEBITAR, feito(), AGORA);
            var naoEncontrada = new ResultadoDoPasso.Recusado("conta-nao-encontrada", "conta não encontrada");

            t.aplicar(Passo.CREDITAR, naoEncontrada, AGORA.plusSeconds(1));
            assertThat(t.situacao()).isEqualTo(SituacaoTransferencia.DEBITADA);
            assertThat(t.tentativas()).isEqualTo(1);

            t.aplicar(Passo.CREDITAR, feito(), AGORA.plusSeconds(3));
            assertThat(t.situacao()).isEqualTo(SituacaoTransferencia.CONCLUIDA);
        }

        @Test
        @DisplayName("passada a janela, conta não encontrada volta a ser recusa e estorna")
        void contaNaoEncontradaDepoisDaJanela() {
            var t = nova();
            t.aplicar(Passo.DEBITAR, feito(), AGORA);

            t.aplicar(
                    Passo.CREDITAR,
                    new ResultadoDoPasso.Recusado("conta-nao-encontrada", "conta não encontrada"),
                    AGORA.plus(TransferenciaPix.JANELA_CONTA_NOVA));

            assertThat(t.situacao()).isEqualTo(SituacaoTransferencia.ESTORNANDO);
        }

        @Test
        @DisplayName("a situação não muda; o mesmo passo volta, com espera exponencial")
        void repeteOMesmoPasso() {
            TransferenciaPix t = nova();
            t.aplicar(Passo.DEBITAR, feito(), AGORA);
            var semResposta = new ResultadoDoPasso.SemResposta("timeout");

            t.aplicar(Passo.CREDITAR, semResposta, AGORA);
            assertThat(t.situacao()).isEqualTo(SituacaoTransferencia.DEBITADA);
            assertThat(t.proximoPasso()).contains(Passo.CREDITAR);
            assertThat(t.proximaTentativaEm()).isEqualTo(AGORA.plusSeconds(1));

            t.aplicar(Passo.CREDITAR, semResposta, AGORA);
            assertThat(t.proximaTentativaEm()).isEqualTo(AGORA.plusSeconds(2));
            t.aplicar(Passo.CREDITAR, semResposta, AGORA);
            assertThat(t.proximaTentativaEm()).isEqualTo(AGORA.plusSeconds(4));
            assertThat(t.tentativas()).isEqualTo(3);

            t.aplicar(Passo.CREDITAR, feito(), AGORA.plusSeconds(5));
            assertThat(t.situacao()).isEqualTo(SituacaoTransferencia.CONCLUIDA);
            assertThat(t.tentativas()).isZero();
        }

        @Test
        @DisplayName("depois de 10 tentativas, pede alerta; a espera para no teto de 1 minuto")
        void alertaETeto() {
            TransferenciaPix t = nova();
            for (int i = 0; i < NovasTentativas.ALERTA; i++) {
                assertThat(t.precisaDeAlerta()).isFalse();
                t.aplicar(Passo.DEBITAR, new ResultadoDoPasso.SemResposta("fora do ar"), AGORA);
            }
            assertThat(t.precisaDeAlerta()).isTrue();
            assertThat(t.proximaTentativaEm()).isEqualTo(AGORA.plus(NovasTentativas.TETO));
        }

        @Test
        @DisplayName("espera: 1, 2, 4, 8, 16, 32 s e depois sempre 60 s")
        void espera() {
            assertThat(NovasTentativas.espera(1)).isEqualTo(Duration.ofSeconds(1));
            assertThat(NovasTentativas.espera(6)).isEqualTo(Duration.ofSeconds(32));
            assertThat(NovasTentativas.espera(7)).isEqualTo(Duration.ofSeconds(60));
            assertThat(NovasTentativas.espera(1000)).isEqualTo(Duration.ofSeconds(60));
            assertThatThrownBy(() -> NovasTentativas.espera(0)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("passo fora de ordem (resposta atrasada): recusado")
    void foraDeOrdem() {
        TransferenciaPix t = nova();
        assertThatThrownBy(() -> t.aplicar(Passo.CREDITAR, feito(), AGORA)).isInstanceOf(IllegalStateException.class);
        t.aplicar(Passo.DEBITAR, feito(), AGORA);
        t.aplicar(Passo.CREDITAR, feito(), AGORA);
        assertThatThrownBy(() -> t.aplicar(Passo.CREDITAR, feito(), AGORA)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("regras de criação: valor positivo em reais, contas diferentes, descrição até 140, chave válida")
    void regrasDeCriacao() {
        assertThatThrownBy(() -> TransferenciaPix.criar("k", JOAO, MARIA, CHAVE, reais("0.00"), null, AGORA))
                .isInstanceOf(TransferenciaInvalidaException.class);
        assertThatThrownBy(() -> TransferenciaPix.criar(
                        "k", JOAO, MARIA, CHAVE, Dinheiro.de(new java.math.BigDecimal("1.00"), Currency.getInstance("USD")), null, AGORA))
                .isInstanceOf(TransferenciaInvalidaException.class);
        assertThatThrownBy(() -> TransferenciaPix.criar(
                        "k", JOAO, new Participante(JOAO.contaId(), "João"), CHAVE, reais("1.00"), null, AGORA))
                .isInstanceOf(TransferenciaInvalidaException.class)
                .hasMessageContaining("mesma conta");
        assertThatThrownBy(() -> TransferenciaPix.criar("k", JOAO, MARIA, CHAVE, reais("1.00"), "x".repeat(141), AGORA))
                .isInstanceOf(TransferenciaInvalidaException.class);
        assertThatThrownBy(() -> TransferenciaPix.criar("com espaço", JOAO, MARIA, CHAVE, reais("1.00"), null, AGORA))
                .isInstanceOf(TransferenciaInvalidaException.class);
    }

    @Test
    @DisplayName("o mesmo pedido repetido é reconhecido; outro pedido com a mesma chave, não")
    void correspondeA() {
        TransferenciaPix t = nova();
        assertThat(t.correspondeA(JOAO.contaId(), CHAVE, reais("100.00"))).isTrue();
        assertThat(t.correspondeA(JOAO.contaId(), CHAVE, reais("100.01"))).isFalse();
        assertThat(t.correspondeA(MARIA.contaId(), CHAVE, reais("100.00"))).isFalse();
    }

    @Test
    @DisplayName("reconstituir do banco devolve o mesmo estado")
    void reconstituir() {
        TransferenciaPix t = nova();
        t.aplicar(Passo.DEBITAR, feito(), AGORA);
        assertThat(TransferenciaPix.reconstituir(t.estado()).estado()).isEqualTo(t.estado());
    }

    @Test
    @DisplayName("chave de idempotência do passo: fixa por transferência e por passo")
    void chaveDoPasso() {
        UUID id = UUID.randomUUID();
        assertThat(Passo.DEBITAR.chaveDeIdempotencia(id)).isEqualTo("pix:" + id + ":envio");
        assertThat(Passo.CREDITAR.chaveDeIdempotencia(id)).isEqualTo("pix:" + id + ":recebimento");
        assertThat(Passo.ESTORNAR.chaveDeIdempotencia(id)).isEqualTo("pix:" + id + ":estorno");
    }
}
