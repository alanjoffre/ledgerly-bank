package io.github.alanjoffre.ledgerly.razao.dominio;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Escrituração: registrar um lançamento nas contas (o verbo contábil é "escriturar").
 *
 * <p><b>Padrão:</b> serviço de domínio (DDD) — uma regra que envolve várias entidades ao mesmo
 * tempo (o lançamento e as contas) e por isso não pertence a nenhuma delas sozinha.
 *
 * <p>É Java puro: recebe as contas já carregadas (e travadas) e devolve o lançamento pronto.
 * Gravar no banco é responsabilidade de quem chama.
 */
public final class Escrituracao {

    private Escrituracao() {}

    /**
     * Aplica os pedidos às contas e devolve o lançamento, com o saldo de cada conta após a partida.
     *
     * <p>Tudo ou nada: as regras do conjunto (partidas dobradas, moeda) são conferidas ANTES de
     * mexer em qualquer conta. Se uma conta recusar a partida (saldo insuficiente, conta
     * bloqueada), a exceção sobe e a transação de banco inteira é desfeita por quem chamou.
     *
     * @param contas as contas envolvidas, por id; precisam estar travadas para atualização
     */
    public static Lancamento escriturar(
            ChaveIdempotencia chave,
            TipoOperacao tipo,
            String descricao,
            List<PedidoDePartida> pedidos,
            Map<UUID, ContaRazao> contas,
            Instant agora) {
        Lancamento.exigirPartidasDobradas(pedidos);
        Lancamento.validarDescricao(descricao);

        List<Partida> partidas = new ArrayList<>(pedidos.size());
        for (PedidoDePartida pedido : pedidos) {
            ContaRazao conta = contas.get(pedido.contaId());
            if (conta == null) {
                throw new ContaNaoEncontradaException("Conta %s não encontrada no razão".formatted(pedido.contaId()));
            }
            var saldoApos = conta.aplicar(pedido.tipo(), pedido.valor());
            partidas.add(new Partida(pedido.contaId(), pedido.tipo(), pedido.valor(), saldoApos));
        }
        return new Lancamento(UUID.randomUUID(), chave, tipo, descricao, partidas, agora);
    }
}
