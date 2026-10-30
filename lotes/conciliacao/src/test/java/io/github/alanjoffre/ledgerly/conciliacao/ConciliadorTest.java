package io.github.alanjoffre.ledgerly.conciliacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.alanjoffre.ledgerly.conciliacao.Divergencia.Tipo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Conciliação Pix × razão")
class ConciliadorTest {

    private static Registro envio(String id, long centavos) {
        return new Registro(UUID.fromString(id), Registro.Tipo.PIX_ENVIO, centavos);
    }

    @Nested
    @DisplayName("o merge join")
    class MergeJoin {

        @Test
        @DisplayName("acha cada tipo de divergência, no lugar certo — inclusive com ids dos dois lados da 'metade' do espaço")
        void tiposDeDivergencia() {
            Registro igual = envio("00000000-0000-0000-0000-000000000001", 100);
            Registro soNoPix = envio("10000000-0000-0000-0000-000000000000", 200);
            Registro valorPix = envio("80000000-0000-0000-0000-000000000000", 300);
            Registro valorRazao = envio("80000000-0000-0000-0000-000000000000", 301);
            Registro tipoRazao = new Registro(UUID.fromString("90000000-0000-0000-0000-000000000000"),
                    Registro.Tipo.PIX_ESTORNO, 400);
            Registro tipoPix = envio("90000000-0000-0000-0000-000000000000", 400);
            Registro soNoRazao = envio("ffffffff-ffff-ffff-ffff-ffffffffffff", 500);

            var pix = new FonteEmMemoria(igual, soNoPix, valorPix, tipoPix);
            var razao = new FonteEmMemoria(igual, valorRazao, tipoRazao, soNoRazao);
            Resultado r = new Conciliador(pix, razao).conciliar(Faixa.tudo());

            assertThat(r.conferidos()).isEqualTo(5);
            assertThat(r.conciliados()).isEqualTo(1);
            assertThat(r.bate()).isFalse();
            assertThat(r.amostra()).extracting(Divergencia::tipo)
                    .containsExactly(Tipo.FALTA_NO_RAZAO, Tipo.DIVERGENTE, Tipo.DIVERGENTE, Tipo.SOBRA_NO_RAZAO);
            assertThat(r.amostra().getFirst().pix()).isEqualTo(soNoPix);
            assertThat(r.amostra().getFirst().razao()).isNull();
            assertThat(r.amostra().getLast().razao()).isEqualTo(soNoRazao);
            assertThat(r.amostra()).extracting(Divergencia::execucao).containsOnly(Conciliador.AVULSA);
        }

        @Test
        @DisplayName("lados iguais: tudo bate; lados vazios: nada a conferir")
        void iguais() {
            var lados = FonteSintetica.par(20_000, 0, 0, 0);
            Resultado r = new Conciliador(lados[0], lados[1]).conciliarEmSequencia(4);
            assertThat(r.bate()).isTrue();
            assertThat(r.conciliados()).isEqualTo(20_000);
            assertThat(new Conciliador(new FonteEmMemoria(), new FonteEmMemoria()).conciliar(Faixa.tudo()))
                    .isEqualTo(Resultado.VAZIO);
        }

        @Test
        @DisplayName("dá o mesmo que a conferência ingênua, com um mapa de todos os registros na memória")
        void comoAConferenciaIngenua() {
            Fonte[] lados = FonteSintetica.par(100_000, 3_000, 2_000, 4_000);
            Resultado r = new Conciliador(lados[0], lados[1]).conciliarEmSequencia(8);

            Map<UUID, Registro> pix = new HashMap<>();
            Map<UUID, Registro> razao = new HashMap<>();
            try (Stream<Registro> p = lados[0].ler(Faixa.tudo()); Stream<Registro> z = lados[1].ler(Faixa.tudo())) {
                p.forEach(x -> pix.put(x.id(), x));
                z.forEach(x -> razao.put(x.id(), x));
            }
            long faltas = pix.keySet().stream().filter(id -> !razao.containsKey(id)).count();
            long sobras = razao.keySet().stream().filter(id -> !pix.containsKey(id)).count();
            long divergentes = pix.entrySet().stream()
                    .filter(e -> razao.containsKey(e.getKey()) && !razao.get(e.getKey()).equals(e.getValue()))
                    .count();

            assertThat(faltas).isBetween(200L, 400L); // ~3 mil por milhão de 100 mil
            assertThat(r.divergencias(Tipo.FALTA_NO_RAZAO)).isEqualTo(faltas);
            assertThat(r.divergencias(Tipo.SOBRA_NO_RAZAO)).isEqualTo(sobras);
            assertThat(r.divergencias(Tipo.DIVERGENTE)).isEqualTo(divergentes);
            assertThat(r.conferidos()).isEqualTo(pix.size() + sobras);
            assertThat(r.amostra()).hasSize(Resultado.LIMITE_DA_AMOSTRA);
        }

        @Test
        @DisplayName("uma fonte fora de ordem ou fora da faixa: para tudo, em vez de dar um resultado errado")
        void contratoDaFonte() {
            Registro a = envio("00000000-0000-0000-0000-000000000001", 1);
            Registro b = envio("00000000-0000-0000-0000-000000000002", 1);
            Fonte foraDeOrdem = faixa -> Stream.of(b, a);
            Fonte repetida = faixa -> Stream.of(a, a);
            Fonte foraDaFaixa = faixa -> Stream.of(envio("ffffffff-0000-0000-0000-000000000000", 1));
            var vazia = new FonteEmMemoria();

            assertThatThrownBy(() -> new Conciliador(foraDeOrdem, vazia).conciliar(Faixa.tudo()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("fora de ordem");
            assertThatThrownBy(() -> new Conciliador(vazia, repetida).conciliar(Faixa.tudo()))
                    .hasMessageContaining("razão entregou os ids fora de ordem");
            assertThatThrownBy(() -> new Conciliador(foraDaFaixa, vazia).conciliar(Faixa.dividir(2).getFirst()))
                    .hasMessageContaining("fora da faixa");
        }
    }

    @Nested
    @DisplayName("em paralelo, com Structured Concurrency")
    class EmParalelo {

        @Test
        @DisplayName("o mesmo resultado da conciliação em sequência, registro por registro da amostra")
        void mesmoResultado() throws InterruptedException {
            Fonte[] lados = FonteSintetica.par(200_000, 500, 500, 500);
            var conciliador = new Conciliador(lados[0], lados[1]);
            assertThat(conciliador.conciliar(16)).isEqualTo(conciliador.conciliarEmSequencia(16));
        }

        @Test
        @DisplayName("cada faixa roda numa virtual thread própria")
        void virtualThreads() throws InterruptedException {
            Map<String, Boolean> threads = new ConcurrentHashMap<>();
            Fonte espia = faixa -> {
                threads.put(Thread.currentThread().toString(), Thread.currentThread().isVirtual());
                return Stream.empty();
            };
            new Conciliador(espia, new FonteEmMemoria()).conciliar(8);
            assertThat(threads).hasSize(8).allSatisfy((nome, virtual) -> assertThat(virtual).isTrue());
        }

        @Test
        @DisplayName("uma faixa falha: o erro sobe e as outras são canceladas na hora, sem ficar rodando soltas")
        void falhaCancelaAsOutras() {
            var canceladas = new AtomicInteger();
            // A falha espera as outras 3 começarem. Sem isso, com núcleos sobrando, ela chega antes de
            // a thread principal criar as outras: faixa nunca iniciada não fica solta, mas também não
            // conta como cancelada (16 falhas em 25 rodadas com 4 núcleos).
            var comecaram = new CountDownLatch(3);
            Fonte lenta = faixa -> {
                if (faixa.inicio().getMostSignificantBits() == 0L) {
                    try {
                        comecaram.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IllegalStateException("banco fora do ar");
                }
                comecaram.countDown();
                long base = faixa.inicio().getMostSignificantBits();
                // 10 mil registros, 10 ms cada: sem cancelamento, 100 s por faixa.
                return LongStream.rangeClosed(1, 10_000).mapToObj(n -> {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        canceladas.incrementAndGet();
                        throw new CancellationException("cancelada");
                    }
                    return new Registro(new UUID(base, n), Registro.Tipo.PIX_ENVIO, 1);
                });
            };
            var conciliador = new Conciliador(lenta, new FonteEmMemoria());

            long inicio = System.nanoTime();
            assertThatThrownBy(() -> conciliador.conciliar(4))
                    .isInstanceOf(StructuredTaskScope.FailedException.class)
                    .cause()
                    .hasMessage("banco fora do ar");
            assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(10));
            assertThat(canceladas).hasValue(3);
        }

        @Test
        @DisplayName("uma faixa presa num laço só de CPU também para quando é cancelada")
        void cancelamentoNoLaco() {
            Thread.currentThread().interrupt();
            Fonte[] lados = FonteSintetica.par(100_000, 0, 0, 0);
            try {
                assertThatThrownBy(() -> new Conciliador(lados[0], lados[1]).conciliar(Faixa.tudo()))
                        .isInstanceOf(CancellationException.class);
            } finally {
                Thread.interrupted();
            }
        }

        @Test
        @DisplayName("o id da execução (ScopedValue) chega às divergências de todas as faixas")
        void execucao() throws Exception {
            Fonte[] lados = FonteSintetica.par(50_000, 2_000, 0, 0);
            var conciliador = new Conciliador(lados[0], lados[1]);
            Resultado r = ScopedValue.where(Conciliador.EXECUCAO, "rodada-28-10").call(() -> conciliador.conciliar(4));

            assertThat(r.amostra()).isNotEmpty().extracting(Divergencia::execucao).containsOnly("rodada-28-10");
            assertThat(Conciliador.EXECUCAO.isBound()).isFalse(); // fora do bloco, some
        }
    }

    @Test
    @DisplayName("a soma de resultados respeita o limite da amostra")
    void somaRespeitaOLimite() {
        List<Divergencia> cheia = new ArrayList<>();
        for (int i = 0; i < Resultado.LIMITE_DA_AMOSTRA; i++) {
            cheia.add(new Divergencia("x", Tipo.SOBRA_NO_RAZAO, UUID.randomUUID(), null, null));
        }
        var a = new Resultado(100, 0, Map.of(Tipo.SOBRA_NO_RAZAO, 100L), cheia);
        Resultado soma = a.somar(a);
        assertThat(soma.amostra()).hasSize(Resultado.LIMITE_DA_AMOSTRA);
        assertThat(soma.divergencias(Tipo.SOBRA_NO_RAZAO)).isEqualTo(200);
        assertThat(soma.totalDeDivergencias()).isEqualTo(200);
        assertThat(soma.divergencias(Tipo.DIVERGENTE)).isZero();
    }
}
