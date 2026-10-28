package io.github.alanjoffre.ledgerly.razao.dominio;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * As <b>contas quentes</b> do razão — Caixa e Liquidação Pix — divididas em sub-contas (ADR-0019).
 *
 * <p><b>O problema:</b> todo depósito e todo saque passam pelo Caixa; todo Pix passa pela
 * Liquidação Pix. Cada lançamento trava as suas contas ({@code SELECT ... FOR UPDATE}, ADR-0009),
 * então dois depósitos em clientes diferentes ainda fazem fila na mesma linha. Medido em 28/10/2026:
 * 124 depósitos por segundo, contra 954 transferências entre clientes no mesmo razão.
 *
 * <p><b>A solução:</b> a conta quente vira N sub-contas, e cada lançamento trava uma só. O saldo da
 * conta é a soma das sub-contas. É a mesma ideia do {@link java.util.concurrent.atomic.LongAdder}
 * do Java: em vez de um contador disputado, vários, somados na leitura.
 *
 * <p><b>Qual sub-conta:</b> a do cliente, pelo hash do id da conta dele. Dois pedidos do mesmo
 * cliente já fariam fila na conta dele de qualquer jeito; clientes diferentes se espalham.
 *
 * <p><b>Os ids:</b> toda conta interna tem os 64 bits altos zerados. O último byte diz a conta-mãe
 * (01 = Caixa, 02 = Liquidação Pix) e o penúltimo, a sub-conta: {@code ...000000000101} é a
 * sub-conta 1 do Caixa. A sub-conta 0 é o próprio id antigo da conta — o histórico continua válido.
 */
public final class ContasQuentes {

    /** Quantas sub-contas a migração V3 criou para cada conta quente. */
    public static final int MAXIMO_DE_SUBCONTAS = 16;

    private final int subcontas;

    /**
     * @param subcontas quantas sub-contas usar, de 1 (sem divisão: o comportamento antigo) até
     *     {@link #MAXIMO_DE_SUBCONTAS}
     */
    public ContasQuentes(int subcontas) {
        if (subcontas < 1 || subcontas > MAXIMO_DE_SUBCONTAS) {
            throw new IllegalArgumentException(
                    "O número de sub-contas deve ficar entre 1 e %d: %d".formatted(MAXIMO_DE_SUBCONTAS, subcontas));
        }
        this.subcontas = subcontas;
    }

    public int subcontas() {
        return subcontas;
    }

    /** A sub-conta do Caixa que um depósito ou saque deste cliente movimenta. */
    public UUID caixaPara(UUID contaDoCliente) {
        return subconta(ContaRazao.CAIXA, indice(contaDoCliente));
    }

    /**
     * A sub-conta da Liquidação Pix em que fica o dinheiro de um envio deste pagador. O
     * recebimento e o estorno não usam esta conta: saem da sub-conta que o envio usou de fato
     * (ver {@link Lancamento#subcontaInterna(UUID)}).
     */
    public UUID liquidacaoPara(UUID contaDoPagador) {
        return subconta(ContaRazao.LIQUIDACAO_PIX, indice(contaDoPagador));
    }

    /** A sub-conta {@code k} de uma conta-mãe. A sub-conta 0 é a própria mãe. */
    public static UUID subconta(UUID mae, int k) {
        if (k < 0 || k >= MAXIMO_DE_SUBCONTAS) {
            throw new IllegalArgumentException("Sub-conta fora do intervalo: " + k);
        }
        return new UUID(0L, mae.getLeastSignificantBits() | ((long) k << 8));
    }

    /** Todas as sub-contas de uma conta-mãe, inclusive as que esta configuração não usa. */
    public static List<UUID> todasAsSubcontas(UUID mae) {
        return IntStream.range(0, MAXIMO_DE_SUBCONTAS).mapToObj(k -> subconta(mae, k)).toList();
    }

    /** A conta-mãe de uma sub-conta (Caixa ou Liquidação Pix); a conta de cliente volta igual. */
    public static UUID contaMae(UUID id) {
        if (!ContaRazao.ehInterna(id)) {
            return id;
        }
        return new UUID(0L, id.getLeastSignificantBits() & 0xFF);
    }

    private int indice(UUID conta) {
        return Math.floorMod(conta.hashCode(), subcontas);
    }
}
