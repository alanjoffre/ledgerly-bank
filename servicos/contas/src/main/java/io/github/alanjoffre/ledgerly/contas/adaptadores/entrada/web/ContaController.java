package io.github.alanjoffre.ledgerly.contas.adaptadores.entrada.web;

import io.github.alanjoffre.ledgerly.contas.adaptadores.entrada.web.Dtos.AbrirContaRequisicao;
import io.github.alanjoffre.ledgerly.contas.adaptadores.entrada.web.Dtos.ContaResposta;
import io.github.alanjoffre.ledgerly.contas.adaptadores.entrada.web.Dtos.TitularidadeRequisicao;
import io.github.alanjoffre.ledgerly.contas.adaptadores.entrada.web.Dtos.TitularResposta;
import io.github.alanjoffre.ledgerly.contas.adaptadores.entrada.web.Dtos.TitularidadeResposta;
import io.github.alanjoffre.ledgerly.contas.aplicacao.ContaService;
import io.github.alanjoffre.ledgerly.contas.dominio.Cpf;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API das contas. O bloqueio é um sub-recurso: {@code POST /bloqueio} cria, {@code DELETE /bloqueio}
 * remove — mais claro que um {@code PATCH} com a situação no corpo.
 */
@RestController
@RequestMapping("/v1/contas")
class ContaController {

    private final ContaService contas;

    ContaController(ContaService contas) {
        this.contas = contas;
    }

    @PostMapping
    ResponseEntity<ContaResposta> abrir(@Valid @RequestBody AbrirContaRequisicao requisicao) {
        var conta = contas.abrir(requisicao.clienteId());
        return ResponseEntity.created(URI.create("/v1/contas/" + conta.id())).body(ContaResposta.de(conta));
    }

    /** Com o saldo, consultado no razão. */
    @GetMapping("/{id}")
    ContaResposta consultar(@PathVariable UUID id) {
        return ContaResposta.de(contas.consultar(id));
    }

    /** Titular e situação, sem o saldo: responde mesmo com o razão fora do ar. Usado pelo Pix. */
    @GetMapping("/{id}/titular")
    TitularResposta consultarTitular(@PathVariable UUID id) {
        var titular = contas.consultarTitular(id);
        return new TitularResposta(
                titular.conta().id(), titular.conta().clienteId(), titular.titularNome(), titular.conta().situacao());
    }

    @PostMapping("/{id}/bloqueio")
    ContaResposta bloquear(@PathVariable UUID id) {
        return ContaResposta.de(contas.bloquear(id));
    }

    @DeleteMapping("/{id}/bloqueio")
    ContaResposta desbloquear(@PathVariable UUID id) {
        return ContaResposta.de(contas.desbloquear(id));
    }

    @PostMapping("/{id}/encerramento")
    ContaResposta encerrar(@PathVariable UUID id) {
        return ContaResposta.de(contas.encerrar(id));
    }

    /**
     * O CPF informado é o do titular? Usado pelo serviço de Pix para conferir uma chave do tipo CPF.
     * {@code POST}, e não {@code GET} com o CPF na URL: endereços vão parar em logs de acesso.
     */
    @PostMapping("/{id}/titularidade")
    TitularidadeResposta conferirTitularidade(
            @PathVariable UUID id, @Valid @RequestBody TitularidadeRequisicao requisicao) {
        return new TitularidadeResposta(contas.titularTemCpf(id, Cpf.de(requisicao.cpf())));
    }
}
