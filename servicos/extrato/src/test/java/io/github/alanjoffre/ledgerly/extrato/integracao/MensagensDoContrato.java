package io.github.alanjoffre.ledgerly.extrato.integracao;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Monta mensagens exatamente no formato do contrato ({@code docs/arquitetura/eventos.md}), como o
 * razão e o Pix publicam. Os testes de integração mandam estas mensagens ao Kafka de verdade.
 */
final class MensagensDoContrato {

    static final String TOPICO_LANCAMENTOS = "razao.lancamentos.v1";
    static final String TOPICO_PIX = "pix.transferencias.v1";

    /** A conta interna Caixa do razão. */
    static final UUID CAIXA = UUID.fromString("00000000-0000-0000-0000-000000000001");
    /** A conta interna Liquidação Pix do razão. */
    static final UUID LIQUIDACAO_PIX = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private MensagensDoContrato() {}

    record Partida(long sequencia, UUID contaId, boolean contaInterna, String tipo, String valor, String saldoApos) {

        String json() {
            return """
                    {"sequencia":%d,"contaId":"%s","contaInterna":%s,"tipo":"%s","valor":"%s","saldoApos":"%s"}"""
                    .formatted(sequencia, contaId, contaInterna, tipo, valor, saldoApos);
        }
    }

    static Partida debito(long sequencia, UUID conta, String valor, String saldoApos) {
        return new Partida(sequencia, conta, ehInterna(conta), "DEBITO", valor, saldoApos);
    }

    static Partida credito(long sequencia, UUID conta, String valor, String saldoApos) {
        return new Partida(sequencia, conta, ehInterna(conta), "CREDITO", valor, saldoApos);
    }

    private static boolean ehInterna(UUID conta) {
        return conta.equals(CAIXA) || conta.equals(LIQUIDACAO_PIX);
    }

    static String lancamento(UUID lancamentoId, String tipo, String descricao, Instant registradoEm, Partida... partidas) {
        String dados = """
                {"lancamentoId":"%s","tipo":"%s","descricao":%s,"moeda":"BRL","registradoEm":"%s","partidas":[%s]}"""
                .formatted(lancamentoId, tipo, descricao == null ? "null" : "\"" + descricao + "\"", registradoEm,
                        Arrays.stream(partidas).map(Partida::json).collect(Collectors.joining(",")));
        return envelope("LancamentoRegistrado", dados);
    }

    static String pixConcluido(
            UUID pixId, UUID pagador, String nomePagador, UUID recebedor, String nomeRecebedor, UUID envio, UUID recebimento) {
        String dados = """
                {"pixId":"%s","valor":"1200.00","moeda":"BRL","descricao":"Aluguel",
                 "pagador":{"contaId":"%s","nome":"%s"},
                 "recebedor":{"contaId":"%s","nome":"%s","tipoChave":"EMAIL","chave":"m***@exemplo.com"},
                 "lancamentoEnvioId":"%s","lancamentoRecebimentoId":"%s","concluidoEm":"%s"}"""
                .formatted(pixId, pagador, nomePagador, recebedor, nomeRecebedor, envio, recebimento, Instant.now());
        return envelope("PixConcluido", dados);
    }

    static String pixEstornado(
            UUID pixId, UUID pagador, String nomePagador, UUID recebedor, String nomeRecebedor,
            UUID envio, UUID estorno, String motivo) {
        String dados = """
                {"pixId":"%s","valor":"1200.00","moeda":"BRL","descricao":"Aluguel",
                 "pagador":{"contaId":"%s","nome":"%s"},
                 "recebedor":{"contaId":"%s","nome":"%s","tipoChave":"EMAIL","chave":"m***@exemplo.com"},
                 "lancamentoEnvioId":"%s","lancamentoEstornoId":"%s","motivo":"%s","estornadoEm":"%s"}"""
                .formatted(pixId, pagador, nomePagador, recebedor, nomeRecebedor, envio, estorno, motivo, Instant.now());
        return envelope("PixEstornado", dados);
    }

    static String envelope(String tipo, String dados) {
        return """
                {"eventoId":"%s","tipo":"%s","versao":1,"ocorridoEm":"%s","dados":%s}"""
                .formatted(UUID.randomUUID(), tipo, Instant.now(), dados);
    }
}
