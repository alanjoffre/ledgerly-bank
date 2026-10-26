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

    public record Transferencia(
            ChaveIdempotencia chave, UUID origemId, UUID destinoId, Dinheiro valor, String descricao) {}
}
