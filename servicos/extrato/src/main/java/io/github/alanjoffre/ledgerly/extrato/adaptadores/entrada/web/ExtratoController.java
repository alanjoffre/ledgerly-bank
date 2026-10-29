package io.github.alanjoffre.ledgerly.extrato.adaptadores.entrada.web;

import io.github.alanjoffre.ledgerly.extrato.adaptadores.entrada.web.Dtos.ExtratoResposta;
import io.github.alanjoffre.ledgerly.extrato.adaptadores.entrada.web.Dtos.SaldoResposta;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.ConsultaDoExtrato;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.PermissaoDeLeitura;
import io.github.alanjoffre.ledgerly.seguranca.Usuario;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API de leitura do extrato. Só {@code GET}: no CQRS, este lado não recebe escrita — quem escreve
 * é o razão, e o extrato se atualiza pelos eventos.
 *
 * <p><b>Consistência eventual</b>: um lançamento recém-feito no razão aparece aqui alguns
 * milissegundos depois (o tempo do Outbox + Kafka + projeção). O campo {@code saldo.sequencia} diz
 * até qual partida do razão este extrato já está atualizado.
 */
@RestController
@RequestMapping("/v1/contas/{contaId}")
class ExtratoController {

    private final ConsultaDoExtrato consulta;

    ExtratoController(ConsultaDoExtrato consulta) {
        this.consulta = consulta;
    }

    @GetMapping("/extrato")
    ExtratoResposta extrato(
            @PathVariable UUID contaId,
            @RequestParam(defaultValue = "50") int limite,
            @RequestParam(required = false) Long antesDe,
            @AuthenticationPrincipal Usuario usuario) {
        return ExtratoResposta.de(consulta.extrato(contaId, limite, antesDe, permissao(usuario)));
    }

    @GetMapping("/saldo")
    SaldoResposta saldo(@PathVariable UUID contaId, @AuthenticationPrincipal Usuario usuario) {
        return SaldoResposta.de(consulta.saldo(contaId, permissao(usuario)));
    }

    /** Analista e serviço leem tudo; o cliente, só o próprio (ADR-0026). */
    private static PermissaoDeLeitura permissao(Usuario usuario) {
        return usuario.ehAnalista() || usuario.ehServico() ? PermissaoDeLeitura.TOTAL : usuario::ehDono;
    }
}
