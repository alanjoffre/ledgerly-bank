package io.github.alanjoffre.ledgerly.razao.adaptadores.entrada.web;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import io.github.alanjoffre.ledgerly.razao.aplicacao.ItemExtrato;
import io.github.alanjoffre.ledgerly.razao.aplicacao.RelatorioIntegridade;
import io.github.alanjoffre.ledgerly.razao.dominio.ContaRazao;
import io.github.alanjoffre.ledgerly.razao.dominio.Lancamento;
import io.github.alanjoffre.ledgerly.razao.dominio.Natureza;
import io.github.alanjoffre.ledgerly.razao.dominio.Partida;
import io.github.alanjoffre.ledgerly.razao.dominio.SituacaoConta;
import io.github.alanjoffre.ledgerly.razao.dominio.TipoOperacao;
import io.github.alanjoffre.ledgerly.razao.dominio.TipoPartida;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Os formatos JSON da API (DTO: <i>Data Transfer Object</i>).
 *
 * <p><b>Valores em dinheiro são texto</b> ({@code "10.50"}), não número: em JavaScript, número é
 * ponto flutuante, e o front-end perderia precisão ao ler. Texto chega exato.
 *
 * <p>Os DTOs ficam separados do domínio de propósito: a API pode mudar de formato sem mexer
 * nas regras, e o domínio pode mudar sem quebrar quem usa a API.
 */
final class Dtos {

    private Dtos() {}

    /** Valor positivo com até 2 casas, por exemplo {@code "10"} ou {@code "10.50"}. */
    static final String PADRAO_VALOR = "^\\d{1,17}(\\.\\d{1,2})?$";

    static final String MENSAGEM_VALOR = "deve ser um valor como \"10.50\" (ponto decimal, até 2 casas)";

    // ---- Entrada ----

    record AbrirContaRequisicao(@NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "deve ser um código ISO 4217, como BRL") String moeda) {}

    record AlterarSituacaoRequisicao(@NotNull SituacaoConta situacao) {}

    record DepositoRequisicao(
            @NotNull UUID contaId,
            @NotBlank @Pattern(regexp = PADRAO_VALOR, message = MENSAGEM_VALOR) String valor,
            @Size(max = Lancamento.TAMANHO_MAXIMO_DESCRICAO) String descricao) {}

    record SaqueRequisicao(
            @NotNull UUID contaId,
            @NotBlank @Pattern(regexp = PADRAO_VALOR, message = MENSAGEM_VALOR) String valor,
            @Size(max = Lancamento.TAMANHO_MAXIMO_DESCRICAO) String descricao) {}

    record TransferenciaRequisicao(
            @NotNull UUID origemId,
            @NotNull UUID destinoId,
            @NotBlank @Pattern(regexp = PADRAO_VALOR, message = MENSAGEM_VALOR) String valor,
            @Size(max = Lancamento.TAMANHO_MAXIMO_DESCRICAO) String descricao) {}

    // ---- Saída ----

    record ContaResposta(UUID id, Natureza natureza, String moeda, String saldo, SituacaoConta situacao) {

        static ContaResposta de(ContaRazao conta) {
            return new ContaResposta(
                    conta.id(),
                    conta.natureza(),
                    conta.moeda().getCurrencyCode(),
                    texto(conta.saldo()),
                    conta.situacao());
        }
    }

    record PartidaResposta(UUID contaId, TipoPartida tipo, String valor, String saldoApos) {

        static PartidaResposta de(Partida partida) {
            return new PartidaResposta(
                    partida.contaId(), partida.tipo(), texto(partida.valor()), texto(partida.saldoApos()));
        }
    }

    record LancamentoResposta(
            UUID id,
            TipoOperacao tipo,
            String descricao,
            String valor,
            String moeda,
            Instant registradoEm,
            List<PartidaResposta> partidas) {

        static LancamentoResposta de(Lancamento lancamento) {
            return new LancamentoResposta(
                    lancamento.id(),
                    lancamento.tipo(),
                    lancamento.descricao(),
                    texto(lancamento.valor()),
                    lancamento.valor().moeda().getCurrencyCode(),
                    lancamento.registradoEm(),
                    lancamento.partidas().stream().map(PartidaResposta::de).toList());
        }
    }

    record ItemExtratoResposta(
            UUID lancamentoId,
            TipoOperacao operacao,
            String descricao,
            TipoPartida tipo,
            String valor,
            String saldoApos,
            Instant registradoEm) {

        static ItemExtratoResposta de(ItemExtrato item) {
            return new ItemExtratoResposta(
                    item.lancamentoId(),
                    item.operacao(),
                    item.descricao(),
                    item.tipo(),
                    texto(item.valor()),
                    texto(item.saldoApos()),
                    item.registradoEm());
        }
    }

    record ExtratoResposta(ContaResposta conta, List<ItemExtratoResposta> itens) {}

    record BalancoResposta(String moeda, String devedoras, String credoras, boolean fecha) {}

    record IntegridadeResposta(
            boolean integro,
            List<BalancoResposta> balancos,
            List<UUID> contasComSaldoDivergente,
            List<UUID> lancamentosDesbalanceados) {

        static IntegridadeResposta de(RelatorioIntegridade relatorio) {
            return new IntegridadeResposta(
                    relatorio.integro(),
                    relatorio.balancos().stream()
                            .map(b -> new BalancoResposta(
                                    b.devedoras().moeda().getCurrencyCode(),
                                    texto(b.devedoras()),
                                    texto(b.credoras()),
                                    b.fecha()))
                            .toList(),
                    relatorio.contasComSaldoDivergente(),
                    relatorio.lancamentosDesbalanceados());
        }
    }

    static String texto(Dinheiro dinheiro) {
        return dinheiro.valor().toPlainString();
    }
}
