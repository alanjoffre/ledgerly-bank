package io.github.alanjoffre.ledgerly.contas.adaptadores.entrada.web;

import io.github.alanjoffre.ledgerly.contas.aplicacao.ContaService.ContaComSaldo;
import io.github.alanjoffre.ledgerly.contas.dominio.Cliente;
import io.github.alanjoffre.ledgerly.contas.dominio.Conta;
import io.github.alanjoffre.ledgerly.contas.dominio.SituacaoConta;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Formatos JSON da API de contas. Dinheiro sai como texto ({@code "10.50"}), como no razão. */
final class Dtos {

    private Dtos() {}

    record CadastrarClienteRequisicao(
            @NotBlank @Size(min = 2, max = Cliente.TAMANHO_MAXIMO_NOME) String nome, @NotBlank String cpf) {}

    record AbrirContaRequisicao(@NotNull UUID clienteId) {}

    record TitularidadeRequisicao(@NotBlank String cpf) {}

    /**
     * Titular e situação, sem saldo e sem CPF — o que outro serviço (o Pix) precisa saber da conta.
     * A abertura entra na Fase 5: o antifraude desconfia de conta recém-aberta recebendo valor alto.
     */
    record TitularResposta(UUID contaId, UUID clienteId, String nome, SituacaoConta situacao, Instant abertaEm) {}

    /** Só sim ou não: o CPF completo do titular nunca sai deste serviço. */
    record TitularidadeResposta(boolean confere) {}

    /** O CPF sai mascarado: quem consulta não precisa do número inteiro (LGPD — minimização). */
    record ClienteResposta(UUID id, String nome, String cpf, Instant cadastradoEm) {

        static ClienteResposta de(Cliente cliente) {
            return new ClienteResposta(cliente.id(), cliente.nome(), cliente.cpf().mascarado(), cliente.cadastradoEm());
        }
    }

    record ContaResposta(
            UUID id,
            UUID clienteId,
            String agencia,
            String numero,
            SituacaoConta situacao,
            Instant abertaEm,
            Instant encerradaEm,
            String saldo,
            String moeda) {

        static ContaResposta de(Conta conta) {
            return new ContaResposta(
                    conta.id(),
                    conta.clienteId(),
                    conta.numero().agencia(),
                    conta.numero().numero() + "-" + conta.numero().digito(),
                    conta.situacao(),
                    conta.abertaEm(),
                    conta.encerradaEm(),
                    null,
                    null);
        }

        static ContaResposta de(ContaComSaldo contaComSaldo) {
            ContaResposta base = de(contaComSaldo.conta());
            return new ContaResposta(
                    base.id(),
                    base.clienteId(),
                    base.agencia(),
                    base.numero(),
                    base.situacao(),
                    base.abertaEm(),
                    base.encerradaEm(),
                    contaComSaldo.saldo().valor().toPlainString(),
                    contaComSaldo.saldo().moeda().getCurrencyCode());
        }
    }
}
