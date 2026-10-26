package io.github.alanjoffre.ledgerly.razao.adaptadores.entrada.web;

import io.github.alanjoffre.ledgerly.razao.adaptadores.entrada.web.Dtos.AbrirContaRequisicao;
import io.github.alanjoffre.ledgerly.razao.adaptadores.entrada.web.Dtos.AlterarSituacaoRequisicao;
import io.github.alanjoffre.ledgerly.razao.adaptadores.entrada.web.Dtos.ContaResposta;
import io.github.alanjoffre.ledgerly.razao.adaptadores.entrada.web.Dtos.ExtratoResposta;
import io.github.alanjoffre.ledgerly.razao.adaptadores.entrada.web.Dtos.ItemExtratoResposta;
import io.github.alanjoffre.ledgerly.razao.aplicacao.ContaRazaoService;
import jakarta.validation.Valid;
import java.util.Currency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API das contas do razão. Quem chama é o serviço de contas, não o cliente final. */
@RestController
@RequestMapping("/v1/contas")
class ContaRazaoController {

    private final ContaRazaoService servico;

    ContaRazaoController(ContaRazaoService servico) {
        this.servico = servico;
    }

    /**
     * Abre a conta. É {@code PUT} (e não {@code POST}) porque quem chama escolhe o id e repetir o
     * pedido não cria outra conta: 201 na primeira vez, 200 nas seguintes.
     */
    @PutMapping("/{id}")
    ResponseEntity<ContaResposta> abrir(@PathVariable UUID id, @Valid @RequestBody AbrirContaRequisicao requisicao) {
        var resultado = servico.abrir(id, Currency.getInstance(requisicao.moeda()));
        return ResponseEntity.status(resultado.criada() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ContaResposta.de(resultado.conta()));
    }

    @GetMapping("/{id}")
    ContaResposta consultar(@PathVariable UUID id) {
        return ContaResposta.de(servico.consultar(id));
    }

    /** Bloqueia ({@code BLOQUEADA}), desbloqueia ({@code ATIVA}) ou encerra ({@code ENCERRADA}). */
    @PutMapping("/{id}/situacao")
    ContaResposta alterarSituacao(
            @PathVariable UUID id, @Valid @RequestBody AlterarSituacaoRequisicao requisicao) {
        return ContaResposta.de(servico.alterarSituacao(id, requisicao.situacao()));
    }

    @GetMapping("/{id}/extrato")
    ExtratoResposta extrato(@PathVariable UUID id, @RequestParam(defaultValue = "50") int limite) {
        var itens = servico.extrato(id, limite).stream().map(ItemExtratoResposta::de).toList();
        return new ExtratoResposta(ContaResposta.de(servico.consultar(id)), itens);
    }
}
