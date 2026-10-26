package io.github.alanjoffre.ledgerly.razao.dominio;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Um lançamento: o registro de um fato contábil (depósito, saque, transferência), com duas ou
 * mais partidas.
 *
 * <p><b>Partidas dobradas:</b> a soma dos débitos é igual à soma dos créditos. O dinheiro não
 * nasce nem some — só muda de conta. Esta regra é conferida aqui e, de novo, pelo banco de dados
 * (gatilho {@code partidas_dobradas}).
 *
 * <p>Imutável: um lançamento errado não se edita, corrige-se com um estorno.
 *
 * @param id           identificador do lançamento
 * @param chave        a chave de idempotência do pedido que o originou
 * @param tipo         o que originou o lançamento
 * @param descricao    texto livre para o extrato (opcional, até 140 caracteres)
 * @param partidas     as linhas de débito e crédito
 * @param registradoEm quando foi escriturado
 */
public record Lancamento(
        UUID id,
        ChaveIdempotencia chave,
        TipoOperacao tipo,
        String descricao,
        List<Partida> partidas,
        Instant registradoEm) {

    public static final int TAMANHO_MAXIMO_DESCRICAO = 140;

    public Lancamento {
        Objects.requireNonNull(id, "id é obrigatório");
        Objects.requireNonNull(chave, "chave é obrigatória");
        Objects.requireNonNull(tipo, "tipo é obrigatório");
        // O PostgreSQL guarda microssegundos; o Java, nanossegundos. Sem cortar aqui, o lançamento
        // devolvido na hora (nanos) seria diferente do mesmo lançamento lido do banco (micros) — e
        // um pedido repetido não receberia exatamente a mesma resposta.
        registradoEm = Objects.requireNonNull(registradoEm, "registradoEm é obrigatório").truncatedTo(ChronoUnit.MICROS);
        validarDescricao(descricao);
        partidas = List.copyOf(Objects.requireNonNull(partidas, "partidas são obrigatórias"));
        exigirPartidasDobradas(partidas.stream().map(Partida::comoPedido).toList());
    }

    /**
     * Confere as regras de um conjunto de partidas: ao menos duas, cada conta uma vez só,
     * mesma moeda, débitos = créditos.
     *
     * @throws LancamentoInvalidoException se alguma regra for quebrada
     */
    public static void exigirPartidasDobradas(List<PedidoDePartida> pedidos) {
        if (pedidos.size() < 2) {
            throw new LancamentoInvalidoException("Um lançamento precisa de pelo menos duas partidas");
        }
        Set<UUID> contas = new HashSet<>();
        for (PedidoDePartida pedido : pedidos) {
            if (!contas.add(pedido.contaId())) {
                throw new LancamentoInvalidoException(
                        "A conta %s aparece mais de uma vez no lançamento".formatted(pedido.contaId()));
            }
        }
        var moeda = pedidos.getFirst().valor().moeda();
        Dinheiro debitos = Dinheiro.zero(moeda);
        Dinheiro creditos = Dinheiro.zero(moeda);
        for (PedidoDePartida pedido : pedidos) {
            if (!pedido.valor().moeda().equals(moeda)) {
                throw new LancamentoInvalidoException("Todas as partidas devem estar na mesma moeda");
            }
            if (pedido.tipo() == TipoPartida.DEBITO) {
                debitos = debitos.somar(pedido.valor());
            } else {
                creditos = creditos.somar(pedido.valor());
            }
        }
        if (!debitos.equals(creditos)) {
            throw new LancamentoInvalidoException(
                    "Partidas dobradas: débitos (%s) diferentes de créditos (%s)".formatted(debitos, creditos));
        }
    }

    static void validarDescricao(String descricao) {
        if (descricao != null && descricao.length() > TAMANHO_MAXIMO_DESCRICAO) {
            throw new LancamentoInvalidoException(
                    "A descrição tem mais de %d caracteres".formatted(TAMANHO_MAXIMO_DESCRICAO));
        }
    }

    /**
     * Diz se este lançamento foi gerado por um pedido igual a este — usado quando a mesma chave
     * de idempotência chega de novo. Pedido igual: devolve o lançamento antigo. Diferente: erro.
     */
    public boolean correspondeA(TipoOperacao outroTipo, List<PedidoDePartida> pedidos) {
        return tipo == outroTipo
                && Set.copyOf(partidas.stream().map(Partida::comoPedido).toList()).equals(Set.copyOf(pedidos));
    }

    /** A partida desta conta neste lançamento. */
    public Partida partidaDa(UUID contaId) {
        return partidas.stream()
                .filter(p -> p.contaId().equals(contaId))
                .findFirst()
                .orElseThrow(() -> new ContaNaoEncontradaException(
                        "A conta %s não participa do lançamento %s".formatted(contaId, id)));
    }

    /** O valor movimentado (a soma dos débitos, que é igual à dos créditos). */
    public Dinheiro valor() {
        return partidas.stream()
                .filter(p -> p.tipo() == TipoPartida.DEBITO)
                .map(Partida::valor)
                .reduce(Dinheiro::somar)
                .orElseThrow();
    }
}
