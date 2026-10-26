package io.github.alanjoffre.ledgerly.razao.adaptadores.entrada.web;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import io.github.alanjoffre.ledgerly.razao.adaptadores.entrada.web.Dtos.DepositoRequisicao;
import io.github.alanjoffre.ledgerly.razao.adaptadores.entrada.web.Dtos.LancamentoResposta;
import io.github.alanjoffre.ledgerly.razao.adaptadores.entrada.web.Dtos.SaqueRequisicao;
import io.github.alanjoffre.ledgerly.razao.adaptadores.entrada.web.Dtos.TransferenciaRequisicao;
import io.github.alanjoffre.ledgerly.razao.aplicacao.Comandos;
import io.github.alanjoffre.ledgerly.razao.aplicacao.LancamentoService;
import io.github.alanjoffre.ledgerly.razao.aplicacao.ResultadoLancamento;
import io.github.alanjoffre.ledgerly.razao.dominio.ChaveIdempotencia;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API dos lançamentos: depósito, saque e transferência.
 *
 * <p>Todo pedido que move dinheiro exige o cabeçalho {@code Idempotency-Key}. Resposta:
 * <ul>
 *   <li>{@code 201 Created} — lançamento novo;</li>
 *   <li>{@code 200 OK} + {@code Idempotent-Replayed: true} — a chave já tinha sido usada para o
 *       mesmo pedido; nada foi movido agora, e o corpo é o lançamento original.</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1")
class LancamentoController {

    static final String CABECALHO_CHAVE = "Idempotency-Key";
    static final String CABECALHO_REPETIDO = "Idempotent-Replayed";

    private final LancamentoService servico;

    LancamentoController(LancamentoService servico) {
        this.servico = servico;
    }

    @PostMapping("/depositos")
    ResponseEntity<LancamentoResposta> depositar(
            @RequestHeader(CABECALHO_CHAVE) String chave, @Valid @RequestBody DepositoRequisicao requisicao) {
        return responder(servico.depositar(new Comandos.Deposito(
                new ChaveIdempotencia(chave),
                requisicao.contaId(),
                Dinheiro.reais(requisicao.valor()),
                requisicao.descricao())));
    }

    @PostMapping("/saques")
    ResponseEntity<LancamentoResposta> sacar(
            @RequestHeader(CABECALHO_CHAVE) String chave, @Valid @RequestBody SaqueRequisicao requisicao) {
        return responder(servico.sacar(new Comandos.Saque(
                new ChaveIdempotencia(chave),
                requisicao.contaId(),
                Dinheiro.reais(requisicao.valor()),
                requisicao.descricao())));
    }

    @PostMapping("/transferencias")
    ResponseEntity<LancamentoResposta> transferir(
            @RequestHeader(CABECALHO_CHAVE) String chave, @Valid @RequestBody TransferenciaRequisicao requisicao) {
        return responder(servico.transferir(new Comandos.Transferencia(
                new ChaveIdempotencia(chave),
                requisicao.origemId(),
                requisicao.destinoId(),
                Dinheiro.reais(requisicao.valor()),
                requisicao.descricao())));
    }

    @GetMapping("/lancamentos/{id}")
    LancamentoResposta consultar(@PathVariable UUID id) {
        return LancamentoResposta.de(servico.consultar(id));
    }

    private static ResponseEntity<LancamentoResposta> responder(ResultadoLancamento resultado) {
        var corpo = LancamentoResposta.de(resultado.lancamento());
        if (resultado.repetido()) {
            return ResponseEntity.ok().header(CABECALHO_REPETIDO, "true").body(corpo);
        }
        return ResponseEntity.created(URI.create("/v1/lancamentos/" + corpo.id())).body(corpo);
    }
}
