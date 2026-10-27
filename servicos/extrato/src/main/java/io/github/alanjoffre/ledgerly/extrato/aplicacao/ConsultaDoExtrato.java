package io.github.alanjoffre.ledgerly.extrato.aplicacao;

import io.github.alanjoffre.ledgerly.extrato.aplicacao.porta.MovimentacaoRepository;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.porta.SaldoRepository;
import io.github.alanjoffre.ledgerly.extrato.dominio.ContaNaoEncontradaException;
import io.github.alanjoffre.ledgerly.extrato.dominio.Movimentacao;
import io.github.alanjoffre.ledgerly.extrato.dominio.Saldo;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * A <b>consulta</b> do CQRS: lê o modelo de leitura como ele está. Não chama o razão, não soma
 * partidas — o saldo e as linhas chegaram prontos.
 *
 * <p><b>Paginação por cursor</b> (<i>keyset pagination</i>): a página seguinte é pedida com
 * {@code antesDe = <sequência da última linha recebida>}, e não com "pule as 50 primeiras"
 * ({@code OFFSET}). Duas vantagens: o banco vai direto ao ponto pelo índice, sem contar e descartar
 * linhas; e um lançamento novo que chega no meio da navegação não empurra as linhas e não faz o
 * cliente ver uma linha repetida na página seguinte.
 */
@Service
public class ConsultaDoExtrato {

    public static final int LIMITE_MINIMO = 1;
    public static final int LIMITE_MAXIMO = 500;

    private final MovimentacaoRepository movimentacoes;
    private final SaldoRepository saldos;

    public ConsultaDoExtrato(MovimentacaoRepository movimentacoes, SaldoRepository saldos) {
        this.movimentacoes = movimentacoes;
        this.saldos = saldos;
    }

    /**
     * Uma página do extrato.
     *
     * @throws IllegalArgumentException    limite fora de 1–500, ou {@code antesDe} não positivo
     * @throws ContaNaoEncontradaException o extrato não tem nada desta conta
     */
    public PaginaDoExtrato extrato(UUID contaId, int limite, Long antesDe) {
        if (limite < LIMITE_MINIMO || limite > LIMITE_MAXIMO) {
            throw new IllegalArgumentException(
                    "O limite deve estar entre %d e %d".formatted(LIMITE_MINIMO, LIMITE_MAXIMO));
        }
        if (antesDe != null && antesDe <= 0) {
            throw new IllegalArgumentException("O parâmetro 'antesDe' deve ser positivo");
        }
        Saldo saldo = saldos.buscar(contaId).orElse(null);
        // Pede uma linha a mais: se ela vier, existe próxima página — sem um COUNT à parte.
        List<Movimentacao> linhas = movimentacoes.pagina(contaId, antesDe, limite + 1);
        if (saldo == null && linhas.isEmpty() && antesDe == null) {
            throw naoEncontrada(contaId);
        }
        boolean temMais = linhas.size() > limite;
        List<Movimentacao> itens = temMais ? linhas.subList(0, limite) : linhas;
        Long proxima = temMais ? itens.getLast().razao().sequencia() : null;
        return new PaginaDoExtrato(contaId, saldo, itens, proxima);
    }

    /**
     * O saldo atual no modelo de leitura.
     *
     * @throws ContaNaoEncontradaException o extrato não tem nada desta conta
     */
    public Saldo saldo(UUID contaId) {
        return saldos.buscar(contaId).orElseThrow(() -> naoEncontrada(contaId));
    }

    private static ContaNaoEncontradaException naoEncontrada(UUID contaId) {
        return new ContaNaoEncontradaException(
                "O extrato não tem movimentos da conta %s (ela não existe, ou ainda não teve lançamento)"
                        .formatted(contaId));
    }
}
