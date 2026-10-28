package io.github.alanjoffre.ledgerly.razao.dominio;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/**
 * Uma conta no razão: tem natureza, saldo e situação.
 *
 * <p><b>Padrão:</b> entidade (DDD) — tem identidade ({@link #id()}) e estado que muda. Toda
 * mudança de saldo passa por {@link #aplicar(TipoPartida, Dinheiro)}, que é onde moram as regras:
 * conta encerrada não movimenta, conta bloqueada não tem saída, saldo de cliente não fica negativo.
 *
 * <p><b>Concorrência:</b> esta classe não é segura para várias threads, e não precisa ser. Quem
 * altera uma conta primeiro a trava no banco ({@code SELECT ... FOR UPDATE}); só uma transação
 * por vez tem a conta em mãos. Ver {@code docs/adr/0009-concorrencia-com-trava-pessimista-ordenada.md}.
 */
public final class ContaRazao {

    /** Conta interna "Caixa" do banco, criada pela migração V1. */
    public static final UUID CAIXA = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /**
     * Conta interna "Liquidação Pix", criada pela migração V2: guarda o dinheiro de um Pix em
     * andamento, entre sair do pagador e chegar ao recebedor (ver ADR-0015).
     */
    public static final UUID LIQUIDACAO_PIX = UUID.fromString("00000000-0000-0000-0000-000000000002");

    /**
     * Diz se o id é de uma conta do próprio banco (Caixa, Liquidação Pix ou uma das suas
     * sub-contas, ver {@link ContasQuentes}), e não de um cliente. Toda conta interna tem os 64 bits
     * altos zerados; um UUID de cliente (versão 4, aleatório) nunca tem.
     */
    public static boolean ehInterna(UUID id) {
        return id.getMostSignificantBits() == 0L;
    }

    private final UUID id;
    private final Natureza natureza;
    private final Currency moeda;
    private final boolean permiteSaldoNegativo;
    private Dinheiro saldo;
    private SituacaoConta situacao;

    private ContaRazao(
            UUID id,
            Natureza natureza,
            Currency moeda,
            boolean permiteSaldoNegativo,
            Dinheiro saldo,
            SituacaoConta situacao) {
        this.id = Objects.requireNonNull(id, "id é obrigatório");
        this.natureza = Objects.requireNonNull(natureza, "natureza é obrigatória");
        this.moeda = Objects.requireNonNull(moeda, "moeda é obrigatória");
        this.permiteSaldoNegativo = permiteSaldoNegativo;
        this.saldo = Objects.requireNonNull(saldo, "saldo é obrigatório");
        this.situacao = Objects.requireNonNull(situacao, "situação é obrigatória");
        if (!saldo.moeda().equals(moeda)) {
            throw new LancamentoInvalidoException("O saldo está em moeda diferente da conta " + id);
        }
    }

    /** Abre a conta de um cliente: natureza credora (o banco deve ao cliente), saldo zero. */
    public static ContaRazao abrirContaDeCliente(UUID id, Currency moeda) {
        return new ContaRazao(id, Natureza.CREDORA, moeda, false, Dinheiro.zero(moeda), SituacaoConta.ATIVA);
    }

    /** Recria uma conta a partir do que está gravado no banco. */
    public static ContaRazao reconstituir(
            UUID id,
            Natureza natureza,
            Currency moeda,
            boolean permiteSaldoNegativo,
            Dinheiro saldo,
            SituacaoConta situacao) {
        return new ContaRazao(id, natureza, moeda, permiteSaldoNegativo, saldo, situacao);
    }

    /**
     * Aplica uma partida ao saldo e devolve o saldo novo.
     *
     * <p>Se a natureza da conta "aumenta com" este tipo de partida, soma; senão, subtrai.
     *
     * @throws ContaInativaException      conta encerrada, ou bloqueada numa saída de dinheiro
     * @throws SaldoInsuficienteException a saída deixaria negativa uma conta que não permite
     */
    public Dinheiro aplicar(TipoPartida tipo, Dinheiro valor) {
        Objects.requireNonNull(tipo, "tipo é obrigatório");
        Objects.requireNonNull(valor, "valor é obrigatório");
        if (!valor.moeda().equals(moeda)) {
            throw new LancamentoInvalidoException(
                    "A conta %s é em %s, mas a partida é em %s"
                            .formatted(id, moeda.getCurrencyCode(), valor.moeda().getCurrencyCode()));
        }
        boolean saida = tipo != natureza.aumentaCom();
        if (situacao == SituacaoConta.ENCERRADA) {
            throw new ContaInativaException("A conta %s está encerrada".formatted(id));
        }
        if (situacao == SituacaoConta.BLOQUEADA && saida) {
            throw new ContaInativaException("A conta %s está bloqueada para saída de dinheiro".formatted(id));
        }
        Dinheiro novoSaldo = saida ? saldo.subtrair(valor) : saldo.somar(valor);
        if (novoSaldo.ehNegativo() && !permiteSaldoNegativo) {
            throw new SaldoInsuficienteException(
                    "Saldo insuficiente na conta %s: saldo %s, débito de %s".formatted(id, saldo, valor));
        }
        saldo = novoSaldo;
        return novoSaldo;
    }

    /**
     * Muda a situação da conta.
     *
     * @throws ContaInativaException         a conta já está encerrada (encerramento é definitivo)
     * @throws EncerramentoComSaldoException encerramento pedido com saldo diferente de zero
     */
    public void alterarSituacao(SituacaoConta nova) {
        Objects.requireNonNull(nova, "situação é obrigatória");
        if (situacao == SituacaoConta.ENCERRADA && nova != SituacaoConta.ENCERRADA) {
            throw new ContaInativaException("A conta %s está encerrada; o encerramento é definitivo".formatted(id));
        }
        if (nova == SituacaoConta.ENCERRADA && !saldo.ehZero()) {
            throw new EncerramentoComSaldoException(
                    "A conta %s tem saldo %s; só encerra com saldo zero".formatted(id, saldo));
        }
        situacao = nova;
    }

    public UUID id() {
        return id;
    }

    public Natureza natureza() {
        return natureza;
    }

    public Currency moeda() {
        return moeda;
    }

    public boolean permiteSaldoNegativo() {
        return permiteSaldoNegativo;
    }

    public Dinheiro saldo() {
        return saldo;
    }

    public SituacaoConta situacao() {
        return situacao;
    }
}
