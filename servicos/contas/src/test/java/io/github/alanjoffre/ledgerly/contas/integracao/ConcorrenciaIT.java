package io.github.alanjoffre.ledgerly.contas.integracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.alanjoffre.ledgerly.contas.aplicacao.ClienteService;
import io.github.alanjoffre.ledgerly.contas.aplicacao.ConflitoDeAtualizacaoException;
import io.github.alanjoffre.ledgerly.contas.aplicacao.ContaService;
import io.github.alanjoffre.ledgerly.contas.aplicacao.porta.ContaRepository;
import io.github.alanjoffre.ledgerly.contas.dominio.Conta;
import io.github.alanjoffre.ledgerly.contas.dominio.Cpf;
import io.github.alanjoffre.ledgerly.contas.dominio.CpfJaCadastradoException;
import io.github.alanjoffre.ledgerly.contas.dominio.CpfsDeTeste;
import io.github.alanjoffre.ledgerly.contas.dominio.SituacaoConta;
import io.github.alanjoffre.ledgerly.contas.dominio.TransicaoInvalidaException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Trava otimista na prática. Diferente do razão (trava pessimista: quem chega depois ESPERA),
 * aqui quem chega depois FALHA e é avisado — bom quando conflito é raro e esperar não compensa.
 */
@DisplayName("Concorrência no serviço de contas (trava otimista)")
class ConcorrenciaIT extends TesteDeIntegracao {

    @Autowired
    private ClienteService clientes;

    @Autowired
    private ContaService contas;

    @Autowired
    private ContaRepository repositorio;

    @Autowired
    private TransactionTemplate transacao;

    private Conta novaConta() {
        var cliente = clientes.cadastrar("Maria Exemplo", new Cpf(CpfsDeTeste.gerar()));
        return contas.abrir(cliente.id());
    }

    @Test
    @DisplayName("gravar uma cópia velha da conta (versão já ultrapassada) é recusado")
    void copiaVelhaRecusada() {
        Conta conta = novaConta();
        Conta copiaVelha = repositorio.buscar(conta.id()).orElseThrow(); // versão 0

        contas.bloquear(conta.id()); // outra operação grava: versão 1

        copiaVelha.encerrar(java.time.Instant.now());
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> repositorio.atualizar(copiaVelha)))
                .isInstanceOf(ConflitoDeAtualizacaoException.class);
        assertThat(contas.consultar(conta.id()).conta().situacao())
                .as("a mudança da outra operação não foi sobrescrita")
                .isEqualTo(SituacaoConta.BLOQUEADA);
    }

    @Test
    @DisplayName("50 pedidos simultâneos de bloqueio da mesma conta: exatamente 1 vence; "
            + "os outros recebem conflito ou transição inválida — nunca dois bloqueios gravados")
    void umSoVence() throws Exception {
        Conta conta = novaConta();

        Map<Class<?>, Long> resultado = emParalelo(50, i -> () -> {
            contas.bloquear(conta.id());
            return null;
        });

        assertThat(resultado.get(Sucesso.class)).isEqualTo(1L);
        assertThat(resultado.keySet())
                .containsAnyOf(ConflitoDeAtualizacaoException.class, TransicaoInvalidaException.class)
                .allMatch(c -> c == Sucesso.class
                        || c == ConflitoDeAtualizacaoException.class
                        || c == TransicaoInvalidaException.class);
        assertThat(jdbc.queryForObject("SELECT versao FROM conta WHERE id = ?", Long.class, conta.id()))
                .as("uma única gravação: versão 0 → 1")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("20 cadastros simultâneos com o mesmo CPF: 1 cliente, 19 recusas")
    void cpfSimultaneo() throws Exception {
        String cpf = CpfsDeTeste.gerar();

        Map<Class<?>, Long> resultado = emParalelo(20, i -> () -> {
            clientes.cadastrar("Pessoa " + i, new Cpf(cpf));
            return null;
        });

        assertThat(resultado).containsEntry(Sucesso.class, 1L).containsEntry(CpfJaCadastradoException.class, 19L);
    }

    private static final class Sucesso {}

    private static Map<Class<?>, Long> emParalelo(int quantidade, Function<Integer, Callable<Void>> tarefa)
            throws Exception {
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Class<?>>> futuros = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < quantidade; i++) {
                Callable<Void> trabalho = tarefa.apply(i);
                futuros.add(executor.submit(() -> {
                    largada.await();
                    try {
                        trabalho.call();
                        return Sucesso.class;
                    } catch (RuntimeException e) {
                        return e.getClass();
                    }
                }));
            }
            largada.countDown();
        }
        List<Class<?>> resultados = new ArrayList<>();
        for (Future<Class<?>> f : futuros) {
            resultados.add(f.get());
        }
        return resultados.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }
}
