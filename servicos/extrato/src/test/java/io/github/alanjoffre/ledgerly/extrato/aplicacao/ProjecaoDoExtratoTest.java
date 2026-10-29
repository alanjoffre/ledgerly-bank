package io.github.alanjoffre.ledgerly.extrato.aplicacao;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos.LancamentoRegistrado;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos.Pagador;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos.PartidaRegistrada;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos.PixConcluido;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos.PixEstornado;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos.Recebedor;
import io.github.alanjoffre.ledgerly.extrato.dominio.Movimentacao;
import io.github.alanjoffre.ledgerly.extrato.dominio.PapelNoPix;
import io.github.alanjoffre.ledgerly.extrato.dominio.SituacaoPix;
import io.github.alanjoffre.ledgerly.extrato.dominio.TipoOperacao;
import io.github.alanjoffre.ledgerly.extrato.dominio.TipoPartida;
import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Projeção: eventos → modelo de leitura")
class ProjecaoDoExtratoTest {

    private static final Instant QUANDO = Instant.parse("2026-10-27T18:30:00Z");
    private static final UUID LIQUIDACAO = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final UUID joao = UUID.randomUUID();
    private final UUID maria = UUID.randomUUID();
    private final UUID pix = UUID.randomUUID();
    private final UUID envio = UUID.randomUUID();
    private final UUID recebimento = UUID.randomUUID();

    private final RepositoriosEmMemoria memoria = new RepositoriosEmMemoria();
    private final ProjecaoDoExtrato projecao = new ProjecaoDoExtrato(memoria, memoria);
    private final ConsultaDoExtrato consulta = new ConsultaDoExtrato(memoria, memoria, conta -> java.util.Optional.empty());

    private static PartidaRegistrada partida(long sequencia, UUID conta, boolean interna, TipoPartida tipo, String saldo) {
        return new PartidaRegistrada(sequencia, conta, interna, tipo, Dinheiro.reais("1200.00"), Dinheiro.reais(saldo));
    }

    private LancamentoRegistrado lancamentoDeEnvio() {
        return new LancamentoRegistrado(envio, TipoOperacao.PIX_ENVIO, "Aluguel", QUANDO, List.of(
                partida(10, joao, false, TipoPartida.DEBITO, "300.00"),
                partida(11, LIQUIDACAO, true, TipoPartida.CREDITO, "1200.00")));
    }

    private LancamentoRegistrado lancamentoDeRecebimento() {
        return new LancamentoRegistrado(recebimento, TipoOperacao.PIX_RECEBIMENTO, "Aluguel", QUANDO, List.of(
                partida(12, LIQUIDACAO, true, TipoPartida.DEBITO, "0.00"),
                partida(13, maria, false, TipoPartida.CREDITO, "1200.00")));
    }

    private PixConcluido pixConcluido() {
        return new PixConcluido(pix, new Pagador(joao, "João da Silva"),
                new Recebedor(maria, "Maria Souza", "EMAIL", "m***@exemplo.com"), envio, recebimento);
    }

    @Test
    @DisplayName("as partidas de contas internas do banco não viram linha nem saldo")
    void contasInternasFicamDeFora() {
        projecao.projetar(lancamentoDeEnvio());
        assertThat(memoria.documentos).hasSize(1);
        assertThat(memoria.saldos).containsOnlyKeys(joao);
    }

    @Test
    @DisplayName("Pix concluído: cada lado vê o nome do outro")
    void pixConcluidoCadaLadoVeOOutro() {
        projecao.projetar(lancamentoDeEnvio());
        projecao.projetar(lancamentoDeRecebimento());
        projecao.projetar(pixConcluido());

        Movimentacao doJoao = consulta.extrato(joao, 10, null).itens().getFirst();
        assertThat(doJoao.descricao()).isEqualTo("Pix enviado para Maria Souza");
        assertThat(doJoao.pix().chave()).isEqualTo("m***@exemplo.com");
        Movimentacao daMaria = consulta.extrato(maria, 10, null).itens().getFirst();
        assertThat(daMaria.descricao()).isEqualTo("Pix recebido de João da Silva");
        assertThat(daMaria.pix().chave()).isNull();
    }

    @Test
    @DisplayName("Pix estornado: as duas linhas são do pagador; o recebedor não vê nada")
    void pixEstornado() {
        UUID estorno = UUID.randomUUID();
        projecao.projetar(lancamentoDeEnvio());
        projecao.projetar(new LancamentoRegistrado(estorno, TipoOperacao.PIX_ESTORNO, null, QUANDO, List.of(
                partida(20, LIQUIDACAO, true, TipoPartida.DEBITO, "0.00"),
                partida(21, joao, false, TipoPartida.CREDITO, "1500.00"))));
        projecao.projetar(new PixEstornado(pix, new Pagador(joao, "João da Silva"),
                new Recebedor(maria, "Maria Souza", "EMAIL", "m***@exemplo.com"), envio, estorno, "conta de destino encerrada"));

        List<Movimentacao> linhas = consulta.extrato(joao, 10, null).itens();
        assertThat(linhas).extracting(Movimentacao::descricao).containsExactly(
                "Estorno do Pix para Maria Souza: conta de destino encerrada",
                "Pix enviado para Maria Souza");
        assertThat(linhas.get(1).pix().situacao()).isEqualTo(SituacaoPix.ESTORNADO);
        assertThat(linhas.get(1).pix().papel()).isEqualTo(PapelNoPix.ENVIADO);
        assertThat(memoria.documentos.keySet()).noneMatch(chave -> chave.contaId().equals(maria));
        assertThat(consulta.saldo(joao).valor()).isEqualTo(Dinheiro.reais("1500.00"));
    }

    @Test
    @DisplayName("Pix devolvido por análise: a saída fica na conta que pagou; a volta, na conta da devolução")
    void pixDevolvido() {
        UUID estorno = UUID.randomUUID();
        UUID outraDoJoao = UUID.randomUUID();
        projecao.projetar(lancamentoDeEnvio());
        projecao.projetar(new LancamentoRegistrado(estorno, TipoOperacao.PIX_ESTORNO, null, QUANDO, List.of(
                partida(20, LIQUIDACAO, true, TipoPartida.DEBITO, "0.00"),
                partida(1, outraDoJoao, false, TipoPartida.CREDITO, "500.00"))));
        projecao.projetar(new PixEstornado(pix, new Pagador(joao, "João da Silva"),
                new Recebedor(maria, "Maria Souza", "EMAIL", "m***@exemplo.com"), envio, estorno,
                "devolvido por análise", outraDoJoao));

        assertThat(consulta.extrato(outraDoJoao, 10, null).itens()).singleElement()
                .satisfies(linha -> assertThat(linha.pix().papel()).isEqualTo(PapelNoPix.ESTORNO));
        assertThat(consulta.extrato(joao, 10, null).itens()).singleElement()
                .satisfies(linha -> assertThat(linha.pix().situacao()).isEqualTo(SituacaoPix.ESTORNADO));
    }

    /**
     * Teste de propriedade: a ordem de chegada não importa. Os três eventos de um Pix (e repetições
     * deles, como na entrega "pelo menos uma vez"), em 200 ordens sorteadas, dão sempre o mesmo
     * modelo de leitura.
     */
    @Test
    @DisplayName("qualquer ordem de chegada, com repetições, dá o mesmo extrato (200 ordens sorteadas)")
    void ordemNaoImporta() {
        projecao.projetar(lancamentoDeEnvio());
        projecao.projetar(lancamentoDeRecebimento());
        projecao.projetar(pixConcluido());
        var esperado = new ArrayList<>(memoria.documentos.entrySet());
        var saldosEsperados = Map.copyOf(memoria.saldos);

        Random sorteio = new Random(42);
        for (int rodada = 0; rodada < 200; rodada++) {
            var memoriaNova = new RepositoriosEmMemoria();
            var projecaoNova = new ProjecaoDoExtrato(memoriaNova, memoriaNova);
            List<Runnable> entregas = new ArrayList<>(List.of(
                    () -> projecaoNova.projetar(lancamentoDeEnvio()),
                    () -> projecaoNova.projetar(lancamentoDeRecebimento()),
                    () -> projecaoNova.projetar(pixConcluido())));
            // Entrega repetida: até duas cópias de eventos sorteados.
            for (int copia = 0; copia < sorteio.nextInt(3); copia++) {
                entregas.add(entregas.get(sorteio.nextInt(3)));
            }
            Collections.shuffle(entregas, sorteio);
            entregas.forEach(Runnable::run);

            assertThat(memoriaNova.documentos.entrySet()).containsExactlyInAnyOrderElementsOf(esperado);
            assertThat(memoriaNova.saldos).isEqualTo(saldosEsperados);
        }
    }
}
