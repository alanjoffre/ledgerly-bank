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

    /** Uma transferência já aprovada pelo antifraude: o próximo passo é o débito. */
    private static TransferenciaPix nova() {
        TransferenciaPix t = criada("100.00");
        t.aplicarAvaliacao(new ResultadoDaAvaliacao.Aprovar(12), AGORA);
        return t;
    }

    private static TransferenciaPix criada(String valor) {
        return TransferenciaPix.criar("chave-1", JOAO, MARIA, CHAVE, reais(valor), "Aluguel", AGORA);
    }

    private static ResultadoDoPasso feito() {
        return new ResultadoDoPasso.Feito(UUID.randomUUID());
    }

    @Nested
    @DisplayName("caminhos da Saga")
    class Caminhos {

        @Test
        @DisplayName("feliz: (APROVADA) → DEBITADA → CONCLUIDA")
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

    @Nested
    @DisplayName("devolução decidida na análise (ADR-0030)")
    class Devolucao {

        private final UUID poupanca = UUID.randomUUID();

        private TransferenciaPix emAnalise() {
            TransferenciaPix t = nova();
            t.aplicar(Passo.DEBITAR, feito(), AGORA);
            t.aplicar(Passo.CREDITAR, new ResultadoDoPasso.Recusado("conta-inativa", "destino encerrado"), AGORA);
            t.aplicar(Passo.ESTORNAR, new ResultadoDoPasso.Recusado("conta-inativa", "pagador encerrado"), AGORA);
            return t;
        }

        @Test
        @DisplayName("EM_ANALISE → ESTORNANDO para a conta decidida → ESTORNADA, com a chave própria da devolução")
        void devolve() {
            TransferenciaPix t = emAnalise();
            t.decidirDevolucao(poupanca, AGORA.plusSeconds(60));

            assertThat(t.situacao()).isEqualTo(SituacaoTransferencia.ESTORNANDO);
            assertThat(t.contaDevolucaoId()).isEqualTo(poupanca);
            assertThat(t.proximoPasso()).contains(Passo.ESTORNAR);
            assertThat(t.contaDoPasso(Passo.ESTORNAR)).isEqualTo(poupanca);
            // Chave nova: a do estorno recusado devolveria a recusa antiga (idempotência do razão).
            assertThat(t.chaveDoPasso(Passo.ESTORNAR)).isEqualTo("pix:" + t.id() + ":devolucao:" + poupanca);
            assertThat(t.tentativas()).isZero();
            assertThat(t.finalizadaEm()).isNull();

            t.aplicar(Passo.ESTORNAR, feito(), AGORA.plusSeconds(61));
            assertThat(t.situacao()).isEqualTo(SituacaoTransferencia.ESTORNADA);
            assertThat(t.contaDevolucaoId()).isEqualTo(poupanca);
        }

        @Test
        @DisplayName("sem decisão, os passos usam as contas e chaves de sempre")
        void semDecisao() {
            TransferenciaPix t = nova();
            assertThat(t.contaDoPasso(Passo.DEBITAR)).isEqualTo(JOAO.contaId());
            assertThat(t.contaDoPasso(Passo.CREDITAR)).isEqualTo(MARIA.contaId());
            assertThat(t.contaDoPasso(Passo.ESTORNAR)).isEqualTo(JOAO.contaId());
            assertThat(t.chaveDoPasso(Passo.ESTORNAR)).isEqualTo("pix:" + t.id() + ":estorno");
            assertThat(t.contaDevolucaoId()).isNull();
        }

        @Test
        @DisplayName("só em análise: concluída, estornada ou em andamento é recusada")
        void soEmAnalise() {
            TransferenciaPix concluida = nova();
            concluida.aplicar(Passo.DEBITAR, feito(), AGORA);
            concluida.aplicar(Passo.CREDITAR, feito(), AGORA);
            assertThatThrownBy(() -> concluida.decidirDevolucao(poupanca, AGORA))
                    .isInstanceOf(TransferenciaForaDeAnaliseException.class)
                    .hasMessageContaining("CONCLUIDA");
            assertThatThrownBy(() -> nova().decidirDevolucao(poupanca, AGORA))
                    .isInstanceOf(TransferenciaForaDeAnaliseException.class);

            TransferenciaPix devolvida = emAnalise();
            devolvida.decidirDevolucao(poupanca, AGORA);
            assertThatThrownBy(() -> devolvida.decidirDevolucao(UUID.randomUUID(), AGORA))
                    .isInstanceOf(TransferenciaForaDeAnaliseException.class);
            assertThat(new TransferenciaForaDeAnaliseException("x").codigo()).isEqualTo("transferencia-fora-de-analise");
        }

        @Test
        @DisplayName("a conta da volta não pode ser a que pagou nem a que recebeu")
        void outraConta() {
            assertThatThrownBy(() -> emAnalise().decidirDevolucao(JOAO.contaId(), AGORA))
                    .isInstanceOf(TransferenciaInvalidaException.class);
            assertThatThrownBy(() -> emAnalise().decidirDevolucao(MARIA.contaId(), AGORA))
                    .isInstanceOf(TransferenciaInvalidaException.class);
            assertThatThrownBy(() -> emAnalise().decidirDevolucao(null, AGORA)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("a decisão sobrevive à gravação: reconstituir mantém a conta da devolução")
        void reconstitui() {
            TransferenciaPix t = emAnalise();
            t.decidirDevolucao(poupanca, AGORA);
            TransferenciaPix lida = TransferenciaPix.reconstituir(t.estado());
            assertThat(lida.contaDevolucaoId()).isEqualTo(poupanca);
            assertThat(lida.chaveDoPasso(Passo.ESTORNAR)).isEqualTo(t.chaveDoPasso(Passo.ESTORNAR));
        }
    }

    @Nested
    @DisplayName("decisão de análise (a linha da auditoria)")
    class Decisao {

        private DecisaoDeAnalise decisao(String justificativa) {
            return new DecisaoDeAnalise(UUID.randomUUID(), UUID.randomUUID(), "sub-1", "carla",
                    DecisaoDeAnalise.Decisao.DEVOLVER, UUID.randomUUID(), justificativa, AGORA);
        }

        @Test
        @DisplayName("justificativa entre 10 e 500 letras, sem os espaços das pontas")
        void justificativa() {
            assertThat(decisao("   Cliente pediu a devolução.  ").justificativa()).isEqualTo("Cliente pediu a devolução.");
            assertThatThrownBy(() -> decisao("curta")).isInstanceOf(TransferenciaInvalidaException.class);
            assertThatThrownBy(() -> decisao("            ")).isInstanceOf(TransferenciaInvalidaException.class);
            assertThatThrownBy(() -> decisao(null)).isInstanceOf(TransferenciaInvalidaException.class);
            assertThatThrownBy(() -> decisao("x".repeat(501))).isInstanceOf(TransferenciaInvalidaException.class);
            assertThat(decisao("x".repeat(500)).justificativa()).hasSize(500);
            assertThat(new ContaDeOutroTitularException("x").codigo()).isEqualTo("conta-de-outro-titular");
        }

        @Test
        @DisplayName("só a devolução tem conta de destino; liberar e recusar, não")
        void contaDeDestinoSoNaDevolucao() {
            assertThatThrownBy(() -> new DecisaoDeAnalise(UUID.randomUUID(), UUID.randomUUID(), "sub-1", "carla",
                    DecisaoDeAnalise.Decisao.LIBERAR, UUID.randomUUID(), "Cliente confirmou por telefone.", AGORA))
                    .isInstanceOf(TransferenciaInvalidaException.class);
            assertThatThrownBy(() -> new DecisaoDeAnalise(UUID.randomUUID(), UUID.randomUUID(), "sub-1", "carla",
                    DecisaoDeAnalise.Decisao.DEVOLVER, null, "Cliente pediu a devolução.", AGORA))
                    .isInstanceOf(TransferenciaInvalidaException.class);
        }
    }

    @Nested
    @DisplayName("antifraude: antes de qualquer dinheiro sair (ADR-0033)")
    class Antifraude {

        @Test
        @DisplayName("criada: precisa de avaliação, e não há passo no razão antes dela")
        void criadaEsperaAAvaliacao() {
            TransferenciaPix t = criada("100.00");
            assertThat(t.precisaDeAvaliacao()).isTrue();
            assertThat(t.proximoPasso()).isEmpty();
            assertThatThrownBy(() -> t.aplicar(Passo.DEBITAR, feito(), AGORA)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("aprovar: APROVADA, com a nota; o próximo passo é o débito")
        void aprovar() {
            TransferenciaPix t = criada("100.00");
            t.aplicarAvaliacao(new ResultadoDaAvaliacao.Aprovar(37), AGORA);
            assertThat(t.situacao()).isEqualTo(SituacaoTransferencia.APROVADA);
            assertThat(t.notaDeRisco()).isEqualTo(37);
            assertThat(t.proximoPasso()).contains(Passo.DEBITAR);
            assertThat(t.finalizadaEm()).isNull();
            assertThatThrownBy(() -> t.aplicarAvaliacao(new ResultadoDaAvaliacao.Aprovar(1), AGORA))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("segurar: RETIDA, sem passo automático, com a nota e os motivos")
        void segurar() {
            TransferenciaPix t = criada("4800.00");
            t.aplicarAvaliacao(new ResultadoDaAvaliacao.Segurar(770, "valor de R$ 4.800,00"), AGORA);
            assertThat(t.situacao()).isEqualTo(SituacaoTransferencia.RETIDA);
            assertThat(t.situacao().ehFinal()).isTrue();
            assertThat(t.situacao().respostaDefinitiva()).isFalse();
            assertThat(t.proximoPasso()).isEmpty();
            assertThat(t.motivo()).isEqualTo("Retido pelo antifraude (nota 770): valor de R$ 4.800,00");
            assertThat(t.lancamentoEnvioId()).isNull();
        }

        @Test
        @DisplayName("recusar: REJEITADA, sem dinheiro movido")
        void recusar() {
            TransferenciaPix t = criada("9000.00");
            t.aplicarAvaliacao(new ResultadoDaAvaliacao.Recusar(990, "8 Pix do pagador na última hora"), AGORA);
            assertThat(t.situacao()).isEqualTo(SituacaoTransferencia.REJEITADA);
            assertThat(t.motivo()).startsWith("Recusado pelo antifraude (nota 990)");
            assertThat(t.finalizadaEm()).isEqualTo(AGORA);
        }

        @Test
        @DisplayName("sem resposta: até R$ 500,00 segue sem nota; acima, fica retida (falha segura)")
        void falhaSegura() {
            TransferenciaPix ate = criada("500.00");
            ate.aplicarAvaliacao(new ResultadoDaAvaliacao.SemResposta("HTTP 503"), AGORA);
            assertThat(ate.situacao()).isEqualTo(SituacaoTransferencia.APROVADA);
            assertThat(ate.notaDeRisco()).isNull();

            TransferenciaPix acima = criada("500.01");
            acima.aplicarAvaliacao(new ResultadoDaAvaliacao.SemResposta("HTTP 503"), AGORA);
            assertThat(acima.situacao()).isEqualTo(SituacaoTransferencia.RETIDA);
            assertThat(acima.motivo()).isEqualTo(
                    "Retido sem avaliação: o valor passa de R$\u00a0500,00 e o antifraude não respondeu (HTTP 503)");
        }

        @Test
        @DisplayName("motivo longo é cortado no tamanho da coluna")
        void motivoLongo() {
            TransferenciaPix t = criada("4800.00");
            t.aplicarAvaliacao(new ResultadoDaAvaliacao.Segurar(770, "x".repeat(1000)), AGORA);
            assertThat(t.motivo()).hasSize(TransferenciaPix.TAMANHO_MAXIMO_MOTIVO).endsWith("…");
        }

        @Test
        @DisplayName("liberar: RETIDA → APROVADA; o débito sai na hora")
        void liberar() {
            TransferenciaPix t = criada("4800.00");
            t.aplicarAvaliacao(new ResultadoDaAvaliacao.Segurar(770, "valor alto"), AGORA);
            t.liberar(AGORA.plusSeconds(60));
            assertThat(t.situacao()).isEqualTo(SituacaoTransferencia.APROVADA);
            assertThat(t.proximoPasso()).contains(Passo.DEBITAR);
            assertThat(t.proximaTentativaEm()).isEqualTo(AGORA.plusSeconds(60));
            assertThat(t.finalizadaEm()).isNull();
            t.aplicar(Passo.DEBITAR, feito(), AGORA.plusSeconds(61));
            assertThat(t.situacao()).isEqualTo(SituacaoTransferencia.DEBITADA);
        }

        @Test
        @DisplayName("recusar na análise: RETIDA → REJEITADA, e o motivo diz quem recusou")
        void recusarNaAnalise() {
            TransferenciaPix t = criada("4800.00");
            t.aplicarAvaliacao(new ResultadoDaAvaliacao.Segurar(770, "valor alto"), AGORA);
            t.recusarNaAnalise(AGORA.plusSeconds(60));
            assertThat(t.situacao()).isEqualTo(SituacaoTransferencia.REJEITADA);
            assertThat(t.motivo()).isEqualTo("Recusado na análise. Retido pelo antifraude (nota 770): valor alto");
            assertThat(t.finalizadaEm()).isEqualTo(AGORA.plusSeconds(60));
        }

        @Test
        @DisplayName("liberar ou recusar o que não está retido: recusado")
        void soORetido() {
            TransferenciaPix t = nova();
            assertThatThrownBy(() -> t.liberar(AGORA)).isInstanceOf(TransferenciaForaDeAnaliseException.class);
            assertThatThrownBy(() -> t.recusarNaAnalise(AGORA)).isInstanceOf(TransferenciaForaDeAnaliseException.class);
        }

        @Test
        @DisplayName("barrado na análise de risco: retido, recusado pelo antifraude ou na análise, e pela falha segura; recusa do razão não")
        void barradoNaAnaliseDeRisco() {
            TransferenciaPix retida = criada("4800.00");
            retida.aplicarAvaliacao(new ResultadoDaAvaliacao.Segurar(770, "valor alto"), AGORA);
            assertThat(retida.barradoNaAnaliseDeRisco()).isTrue();
            retida.recusarNaAnalise(AGORA);
            assertThat(retida.barradoNaAnaliseDeRisco()).isTrue();

            TransferenciaPix recusada = criada("9000.00");
            recusada.aplicarAvaliacao(new ResultadoDaAvaliacao.Recusar(990, "rajada"), AGORA);
            assertThat(recusada.barradoNaAnaliseDeRisco()).isTrue();

            TransferenciaPix semResposta = criada("500.01");
            semResposta.aplicarAvaliacao(new ResultadoDaAvaliacao.SemResposta("HTTP 503"), AGORA);
            assertThat(semResposta.barradoNaAnaliseDeRisco()).isTrue();

            TransferenciaPix semSaldo = nova();
            assertThat(semSaldo.barradoNaAnaliseDeRisco()).isFalse();
            semSaldo.aplicar(Passo.DEBITAR, new ResultadoDoPasso.Recusado("saldo-insuficiente", "Saldo insuficiente"), AGORA);
            assertThat(semSaldo.situacao()).isEqualTo(SituacaoTransferencia.REJEITADA);
            assertThat(semSaldo.barradoNaAnaliseDeRisco()).isFalse();

            TransferenciaPix liberada = criada("4800.00");
            liberada.aplicarAvaliacao(new ResultadoDaAvaliacao.Segurar(770, "valor alto"), AGORA);
            liberada.liberar(AGORA);
            assertThat(liberada.barradoNaAnaliseDeRisco()).isFalse();
        }

        @Test
        @DisplayName("a nota sobrevive à ida e volta do banco")
        void reconstituir() {
            TransferenciaPix t = nova();
            assertThat(TransferenciaPix.reconstituir(t.estado()).notaDeRisco()).isEqualTo(12);
        }
    }

    @Nested
    @DisplayName("os limites")
    class Limites {

        private TransferenciaPix comDescricao(String descricao) {
            return TransferenciaPix.criar("chave-1", JOAO, MARIA, CHAVE, reais("1.00"), descricao, AGORA);
        }

        private TransferenciaPix comChave(String chave) {
            return TransferenciaPix.criar(chave, JOAO, MARIA, CHAVE, reais("1.00"), "Aluguel", AGORA);
        }

        @Test
        @DisplayName("descrição: 140 caracteres passa, 141 não")
        void descricao() {
            assertThat(comDescricao("x".repeat(140)).descricao()).hasSize(140);
            assertThatThrownBy(() -> comDescricao("x".repeat(141))).isInstanceOf(TransferenciaInvalidaException.class);
        }

        @Test
        @DisplayName("chave de idempotência: 100 caracteres passa, 101 não; do ! ao ~ passa, espaço e DEL não")
        void chave() {
            assertThat(comChave("x".repeat(100)).chaveIdempotencia()).hasSize(100);
            assertThat(comChave("!~").chaveIdempotencia()).isEqualTo("!~");
            assertThatThrownBy(() -> comChave("x".repeat(101))).isInstanceOf(TransferenciaInvalidaException.class);
            assertThatThrownBy(() -> comChave("a b")).isInstanceOf(TransferenciaInvalidaException.class);
            assertThatThrownBy(() -> comChave("a\u007f")).isInstanceOf(TransferenciaInvalidaException.class);
        }

        @Test
        @DisplayName("motivo: 400 caracteres ficam inteiros; 401 são cortados com reticências")
        void motivo() {
            // "Retido pelo antifraude (nota 770): " tem 35 caracteres.
            TransferenciaPix inteiro = criada("100.00");
            inteiro.aplicarAvaliacao(new ResultadoDaAvaliacao.Segurar(770, "x".repeat(365)), AGORA);
            assertThat(inteiro.motivo()).hasSize(400).endsWith("x");

            TransferenciaPix cortado = criada("100.00");
            cortado.aplicarAvaliacao(new ResultadoDaAvaliacao.Segurar(770, "x".repeat(366)), AGORA);
            assertThat(cortado.motivo()).hasSize(400).endsWith("…");
        }

        @Test
        @DisplayName("a resposta só é definitiva no fim de verdade: retido e em andamento respondem 202")
        void respostaDefinitiva() {
            assertThat(SituacaoTransferencia.CONCLUIDA.respostaDefinitiva()).isTrue();
            assertThat(SituacaoTransferencia.REJEITADA.respostaDefinitiva()).isTrue();
            assertThat(SituacaoTransferencia.RETIDA.respostaDefinitiva()).isFalse();
            assertThat(SituacaoTransferencia.CRIADA.respostaDefinitiva()).isFalse();
        }

        @Test
        @DisplayName("justificativa da análise: de 10 a 500 caracteres, sem contar os espaços das pontas")
        void justificativa() {
            assertThat(decisao("x".repeat(10)).justificativa()).hasSize(10);
            assertThat(decisao("x".repeat(500)).justificativa()).hasSize(500);
            assertThatThrownBy(() -> decisao("x".repeat(9))).isInstanceOf(TransferenciaInvalidaException.class);
            assertThatThrownBy(() -> decisao("x".repeat(501))).isInstanceOf(TransferenciaInvalidaException.class);
        }

        private DecisaoDeAnalise decisao(String justificativa) {
            return new DecisaoDeAnalise(UUID.randomUUID(), UUID.randomUUID(), "analista-1", "Analista",
                    DecisaoDeAnalise.Decisao.LIBERAR, null, justificativa, AGORA);
        }
    }
}
