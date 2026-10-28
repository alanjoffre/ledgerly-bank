package io.github.alanjoffre.ledgerly.razao.aplicacao;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import io.github.alanjoffre.ledgerly.razao.dominio.ChaveIdempotencia;
import java.util.UUID;

/**
 * Os pedidos que chegam aos casos de uso de lançamento.
 *
 * <p><b>Padrão:</b> <i>Command</i> — um objeto imutável que descreve uma intenção. Separa "o que
 * foi pedido" (aqui) de "como chegou" (JSON, Kafka...) — o adaptador web traduz JSON em comando.
 */
public final class Comandos {

    private Comandos() {}

    public record Deposito(ChaveIdempotencia chave, UUID contaId, Dinheiro valor, String descricao) {}

    public record Saque(ChaveIdempotencia chave, UUID contaId, Dinheiro valor, String descricao) {}

    /**
     * Um movimento do Pix na conta de um cliente: envio, recebimento ou estorno. A outra ponta é
     * sempre a conta interna Liquidação Pix.
     */
    public record MovimentoPix(ChaveIdempotencia chave, UUID contaId, Dinheiro valor, String descricao) {}

    /**
     * Recebimento ou estorno de Pix: o dinheiro sai da Liquidação Pix. Aponta para o lançamento de
     * envio que pôs esse dinheiro lá — o razão tira da mesma sub-conta e confere que o envio ainda
     * não teve saída (ADR-0019).
     */
    public record SaidaDaLiquidacao(
            ChaveIdempotencia chave, UUID contaId, Dinheiro valor, String descricao, UUID lancamentoEnvioId) {}

    public record Transferencia(
            ChaveIdempotencia chave, UUID origemId, UUID destinoId, Dinheiro valor, String descricao) {}
}
