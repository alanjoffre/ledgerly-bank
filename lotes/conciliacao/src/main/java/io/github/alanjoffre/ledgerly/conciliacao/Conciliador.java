package io.github.alanjoffre.ledgerly.conciliacao;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.stream.Stream;

/**
 * Concilia o Pix com o razão: para cada id de lançamento, os dois lados contam a mesma história?
 *
 * <p><b>Merge join</b> (junção por intercalação): os dois lados chegam ordenados por id, então
 * basta andar com um dedo em cada lista, como quem confere duas listas telefônicas. O menor id
 * dos dois avança; ids iguais são comparados. Memória constante, uma passada só — dá para
 * conciliar milhões de registros sem carregar nenhum lado inteiro.
 *
 * <p><b>Em paralelo, com Structured Concurrency</b> ({@link StructuredTaskScope}, preview no Java
 * 25): o espaço de ids é dividido em {@link Faixa}s e cada faixa vira uma subtarefa, numa virtual
 * thread. "Estruturada" quer dizer que as subtarefas vivem dentro de um bloco, como variáveis
 * locais: o bloco só termina quando todas terminam; se uma falha, as outras são canceladas e o erro
 * sobe — nenhuma thread fica rodando solta depois que a conciliação respondeu.
 *
 * <p><b>O contexto da execução</b> ({@link #EXECUCAO}, um {@link ScopedValue}): o id da execução
 * vai em cada divergência, para a auditoria saber qual rodada a achou. Em vez de passar o id por
 * parâmetro em cada método, quem chama o liga uma vez ({@code ScopedValue.where(...)}) e as
 * subtarefas o herdam. Diferente do {@code ThreadLocal}: não pode ser alterado no meio do caminho,
 * e some sozinho no fim do bloco.
 */
public final class Conciliador {

    /** O id da execução em andamento; quem chama liga com {@code ScopedValue.where}. */
    public static final ScopedValue<String> EXECUCAO = ScopedValue.newInstance();

    /** A execução que aparece nas divergências quando ninguém ligou {@link #EXECUCAO}. */
    public static final String AVULSA = "avulsa";

    /** De quantos em quantos registros a faixa confere se foi cancelada. */
    private static final int CONFERIR_CANCELAMENTO_A_CADA = 4096;

    private final Fonte pix;
    private final Fonte razao;

    public Conciliador(Fonte pix, Fonte razao) {
        this.pix = Objects.requireNonNull(pix, "pix");
        this.razao = Objects.requireNonNull(razao, "razao");
    }

    /**
     * Concilia tudo em paralelo: uma subtarefa por faixa, cada uma numa virtual thread.
     *
     * @throws StructuredTaskScope.FailedException se uma faixa falhar (a causa vem dentro); as
     *     outras são canceladas
     */
    public Resultado conciliar(int partes) throws InterruptedException {
        List<Faixa> faixas = Faixa.dividir(partes);
        // open(): todas precisam dar certo; a primeira falha cancela as outras.
        try (var escopo = StructuredTaskScope.open()) {
            List<Subtask<Resultado>> tarefas = new ArrayList<>(faixas.size());
            for (Faixa faixa : faixas) {
                tarefas.add(escopo.fork(() -> conciliar(faixa)));
            }
            escopo.join();
            // A soma é na ordem das faixas: o resultado é o mesmo da conciliação em sequência.
            return tarefas.stream().map(Subtask::get).reduce(Resultado.VAZIO, Resultado::somar);
        }
    }

    /** As mesmas faixas, uma depois da outra, na thread de quem chama: a referência da medição. */
    public Resultado conciliarEmSequencia(int partes) {
        return Faixa.dividir(partes).stream().map(this::conciliar).reduce(Resultado.VAZIO, Resultado::somar);
    }

    /** Concilia uma faixa só: o merge join. */
    public Resultado conciliar(Faixa faixa) {
        var conta = new Contagem(EXECUCAO.orElse(AVULSA));
        try (Stream<Registro> ladoPix = pix.ler(faixa);
                Stream<Registro> ladoRazao = razao.ler(faixa)) {
            var p = new Leitor("Pix", ladoPix.iterator(), faixa);
            var r = new Leitor("razão", ladoRazao.iterator(), faixa);
            long passos = 0;
            while (p.atual != null || r.atual != null) {
                if (++passos % CONFERIR_CANCELAMENTO_A_CADA == 0 && Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("Conciliação da faixa %s cancelada".formatted(faixa));
                }
                int ordem = p.atual == null ? 1 : r.atual == null ? -1 : OrdemDosIds.comparar(p.atual.id(), r.atual.id());
                if (ordem < 0) {
                    conta.divergencia(Divergencia.Tipo.FALTA_NO_RAZAO, p.atual.id(), p.atual, null);
                    p.avancar();
                } else if (ordem > 0) {
                    conta.divergencia(Divergencia.Tipo.SOBRA_NO_RAZAO, r.atual.id(), null, r.atual);
                    r.avancar();
                } else {
                    if (p.atual.equals(r.atual)) {
                        conta.conciliado();
                    } else {
                        conta.divergencia(Divergencia.Tipo.DIVERGENTE, p.atual.id(), p.atual, r.atual);
                    }
                    p.avancar();
                    r.avancar();
                }
            }
        }
        return conta.resultado();
    }

    /** Um dedo numa das listas: o registro atual, conferindo a ordem e a faixa a cada passo. */
    private static final class Leitor {

        private final String lado;
        private final Iterator<Registro> registros;
        private final Faixa faixa;
        private Registro atual;

        Leitor(String lado, Iterator<Registro> registros, Faixa faixa) {
            this.lado = lado;
            this.registros = registros;
            this.faixa = faixa;
            avancar();
        }

        void avancar() {
            UUID anterior = atual == null ? null : atual.id();
            atual = registros.hasNext() ? registros.next() : null;
            if (atual == null) {
                return;
            }
            if (!faixa.contem(atual.id())) {
                throw new IllegalStateException("O %s entregou o id %s, fora da faixa %s".formatted(lado, atual.id(), faixa));
            }
            if (anterior != null && OrdemDosIds.comparar(anterior, atual.id()) >= 0) {
                throw new IllegalStateException(
                        "O %s entregou os ids fora de ordem: %s depois de %s".formatted(lado, atual.id(), anterior));
            }
        }
    }

    /**
     * O placar de UMA faixa. Cada subtarefa tem o seu, então ninguém disputa nada: sem trava, sem
     * contador atômico. Os placares só se juntam no fim ({@link Resultado#somar}).
     */
    private static final class Contagem {

        private final String execucao;
        private long conferidos;
        private long conciliados;
        private final Map<Divergencia.Tipo, Long> divergencias = new EnumMap<>(Divergencia.Tipo.class);
        private final List<Divergencia> amostra = new ArrayList<>();

        Contagem(String execucao) {
            this.execucao = execucao;
        }

        void conciliado() {
            conferidos++;
            conciliados++;
        }

        void divergencia(Divergencia.Tipo tipo, UUID id, Registro pix, Registro razao) {
            conferidos++;
            divergencias.merge(tipo, 1L, Long::sum);
            if (amostra.size() < Resultado.LIMITE_DA_AMOSTRA) {
                amostra.add(new Divergencia(execucao, tipo, id, pix, razao));
            }
        }

        Resultado resultado() {
            return new Resultado(conferidos, conciliados, divergencias, amostra);
        }
    }
}
