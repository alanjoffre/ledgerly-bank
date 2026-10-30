package io.github.alanjoffre.ledgerly.assistente.aplicacao.porta;

import io.github.alanjoffre.ledgerly.assistente.aplicacao.BancarioIndisponivelException;
import io.github.alanjoffre.ledgerly.assistente.aplicacao.Cliente;
import io.github.alanjoffre.ledgerly.assistente.dominio.Conta;
import io.github.alanjoffre.ledgerly.assistente.dominio.Lancamento;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Porta de saída: o que o assistente lê das contas da pessoa, sempre com o token dela.
 *
 * @throws BancarioIndisponivelException quando o contas ou o extrato não responde
 */
public interface Bancario {

    List<Conta> contas(Cliente cliente);

    BigDecimal saldo(Cliente cliente, UUID contaId);

    /** Os lançamentos mais novos primeiro, até {@code limite}. */
    List<Lancamento> lancamentos(Cliente cliente, UUID contaId, int limite);
}
