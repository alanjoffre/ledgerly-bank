package io.github.alanjoffre.ledgerly.contas.dominio;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Conta de um cliente, com o seu ciclo de vida.
 *
 * <p><b>Padrão:</b> máquina de estados. As transições permitidas são:
 *
 * <pre>
 *            bloquear()             encerrar()
 *   ATIVA  ────────────▶ BLOQUEADA      ATIVA ─────▶ ENCERRADA (definitivo)
 *          ◀────────────
 *            desbloquear()
 * </pre>
 *
 * Qualquer outra (encerrar uma conta bloqueada, desbloquear uma ativa...) é recusada. Encerrar uma
 * conta bloqueada é recusado de propósito: o bloqueio costuma ter um motivo (fraude, ordem judicial)
 * que precisa ser resolvido antes.
 *
 * <p>O saldo NÃO mora aqui: é do serviço razão. Esta classe cuida do cadastro e da situação.
 *
 * <p><b>Versão (trava otimista):</b> {@link #versao()} é o número da versão lida do banco. Ao
 * gravar, o banco confere se ainda é a mesma; se outra transação gravou antes, a gravação falha
 * em vez de sobrescrever a mudança alheia. Ver {@code docs/adr/0009}.
 */
public final class Conta {

    private final UUID id;
    private final UUID clienteId;
    private final NumeroConta numero;
    private final Instant abertaEm;
    private final long versao;
    private SituacaoConta situacao;
    private Instant encerradaEm;

    private Conta(
            UUID id,
            UUID clienteId,
            NumeroConta numero,
            SituacaoConta situacao,
            Instant abertaEm,
            Instant encerradaEm,
            long versao) {
        this.id = Objects.requireNonNull(id, "id é obrigatório");
        this.clienteId = Objects.requireNonNull(clienteId, "clienteId é obrigatório");
        this.numero = Objects.requireNonNull(numero, "número é obrigatório");
        this.situacao = Objects.requireNonNull(situacao, "situação é obrigatória");
        // Precisão do PostgreSQL (microssegundos): o que se devolve é o que se lê depois.
        this.abertaEm = Objects.requireNonNull(abertaEm, "abertaEm é obrigatório").truncatedTo(ChronoUnit.MICROS);
        this.encerradaEm = encerradaEm == null ? null : encerradaEm.truncatedTo(ChronoUnit.MICROS);
        this.versao = versao;
    }

    public static Conta abrir(UUID clienteId, NumeroConta numero, Instant agora) {
        return new Conta(UUID.randomUUID(), clienteId, numero, SituacaoConta.ATIVA, agora, null, 0);
    }

    public static Conta reconstituir(
            UUID id,
            UUID clienteId,
            NumeroConta numero,
            SituacaoConta situacao,
            Instant abertaEm,
            Instant encerradaEm,
            long versao) {
        return new Conta(id, clienteId, numero, situacao, abertaEm, encerradaEm, versao);
    }

    public void bloquear() {
        transitar(SituacaoConta.ATIVA, SituacaoConta.BLOQUEADA);
    }

    public void desbloquear() {
        transitar(SituacaoConta.BLOQUEADA, SituacaoConta.ATIVA);
    }

    /** Encerra a conta. O saldo zero é conferido pelo razão, que é o dono do saldo. */
    public void encerrar(Instant agora) {
        transitar(SituacaoConta.ATIVA, SituacaoConta.ENCERRADA);
        encerradaEm = Objects.requireNonNull(agora, "agora é obrigatório").truncatedTo(ChronoUnit.MICROS);
    }

    private void transitar(SituacaoConta de, SituacaoConta para) {
        if (situacao != de) {
            throw new TransicaoInvalidaException(
                    "A conta %s está %s; só pode ir para %s a partir de %s".formatted(id, situacao, para, de));
        }
        situacao = para;
    }

    public UUID id() {
        return id;
    }

    public UUID clienteId() {
        return clienteId;
    }

    public NumeroConta numero() {
        return numero;
    }

    public SituacaoConta situacao() {
        return situacao;
    }

    public Instant abertaEm() {
        return abertaEm;
    }

    public Instant encerradaEm() {
        return encerradaEm;
    }

    public long versao() {
        return versao;
    }
}
