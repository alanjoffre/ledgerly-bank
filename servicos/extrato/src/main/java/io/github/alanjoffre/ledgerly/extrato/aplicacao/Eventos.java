package io.github.alanjoffre.ledgerly.extrato.aplicacao;

import io.github.alanjoffre.ledgerly.extrato.dominio.TipoOperacao;
import io.github.alanjoffre.ledgerly.extrato.dominio.TipoPartida;
import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Os eventos que a projeção entende, já em tipos Java (UUID, {@link Dinheiro}, {@link Instant}).
 *
 * <p>O adaptador do Kafka lê o JSON do contrato ({@code docs/arquitetura/eventos.md}) e entrega
 * estes objetos. Só os campos que o extrato usa estão aqui: um campo novo no evento não quebra
 * nada, e o extrato não depende do que não lê.
 */
public final class Eventos {

    private Eventos() {}

    /** Um lançamento do razão ({@code razao.lancamentos.v1}). */
    public record LancamentoRegistrado(
            UUID lancamentoId,
            TipoOperacao tipo,
            String descricao,
            Instant registradoEm,
            List<PartidaRegistrada> partidas) {

        public LancamentoRegistrado {
            Objects.requireNonNull(lancamentoId, "lancamentoId é obrigatório");
            Objects.requireNonNull(tipo, "tipo é obrigatório");
            Objects.requireNonNull(registradoEm, "registradoEm é obrigatório");
            partidas = List.copyOf(Objects.requireNonNull(partidas, "partidas são obrigatórias"));
        }
    }

    /** Uma partida de um lançamento: uma conta, um débito ou crédito, e o saldo depois. */
    public record PartidaRegistrada(
            long sequencia, UUID contaId, boolean contaInterna, TipoPartida tipo, Dinheiro valor, Dinheiro saldoApos) {

        public PartidaRegistrada {
            Objects.requireNonNull(contaId, "contaId é obrigatório");
        }
    }

    /** Quem pagou. */
    public record Pagador(UUID contaId, String nome) {

        public Pagador {
            Objects.requireNonNull(contaId, "contaId do pagador é obrigatório");
            Objects.requireNonNull(nome, "nome do pagador é obrigatório");
        }
    }

    /** Quem recebeu, e por qual chave (já mascarada pelo Pix). */
    public record Recebedor(UUID contaId, String nome, String tipoChave, String chave) {

        public Recebedor {
            Objects.requireNonNull(contaId, "contaId do recebedor é obrigatório");
            Objects.requireNonNull(nome, "nome do recebedor é obrigatório");
        }
    }

    /** Um Pix que chegou ao recebedor ({@code pix.transferencias.v1}). */
    public record PixConcluido(
            UUID pixId, Pagador pagador, Recebedor recebedor, UUID lancamentoEnvioId, UUID lancamentoRecebimentoId) {

        public PixConcluido {
            Objects.requireNonNull(pixId, "pixId é obrigatório");
            Objects.requireNonNull(pagador, "pagador é obrigatório");
            Objects.requireNonNull(recebedor, "recebedor é obrigatório");
            Objects.requireNonNull(lancamentoEnvioId, "lancamentoEnvioId é obrigatório");
            Objects.requireNonNull(lancamentoRecebimentoId, "lancamentoRecebimentoId é obrigatório");
        }
    }

    /**
     * Um Pix cujo crédito foi recusado, e o dinheiro voltou ao pagador ({@code pix.transferencias.v1}).
     *
     * @param contaDevolucaoId só na devolução de um Pix em análise (ADR-0030): a conta do mesmo
     *     titular que recebeu o dinheiro, porque a que pagou foi encerrada. Campo novo e opcional —
     *     os eventos antigos não o têm, e para eles a volta é na conta que pagou.
     */
    public record PixEstornado(
            UUID pixId,
            Pagador pagador,
            Recebedor recebedor,
            UUID lancamentoEnvioId,
            UUID lancamentoEstornoId,
            String motivo,
            UUID contaDevolucaoId) {

        public PixEstornado(
                UUID pixId, Pagador pagador, Recebedor recebedor, UUID lancamentoEnvioId, UUID lancamentoEstornoId,
                String motivo) {
            this(pixId, pagador, recebedor, lancamentoEnvioId, lancamentoEstornoId, motivo, null);
        }

        /** A conta em que o dinheiro voltou: a da devolução, se houve; senão, a que pagou. */
        public UUID contaDaVolta() {
            return contaDevolucaoId != null ? contaDevolucaoId : pagador.contaId();
        }

        public PixEstornado {
            Objects.requireNonNull(pixId, "pixId é obrigatório");
            Objects.requireNonNull(pagador, "pagador é obrigatório");
            Objects.requireNonNull(recebedor, "recebedor é obrigatório");
            Objects.requireNonNull(lancamentoEnvioId, "lancamentoEnvioId é obrigatório");
            Objects.requireNonNull(lancamentoEstornoId, "lancamentoEstornoId é obrigatório");
        }
    }
}
