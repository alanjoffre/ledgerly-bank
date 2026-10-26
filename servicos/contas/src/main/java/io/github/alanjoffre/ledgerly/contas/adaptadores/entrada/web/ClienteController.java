package io.github.alanjoffre.ledgerly.contas.adaptadores.entrada.web;

import io.github.alanjoffre.ledgerly.contas.adaptadores.entrada.web.Dtos.CadastrarClienteRequisicao;
import io.github.alanjoffre.ledgerly.contas.adaptadores.entrada.web.Dtos.ClienteResposta;
import io.github.alanjoffre.ledgerly.contas.adaptadores.entrada.web.Dtos.ContaResposta;
import io.github.alanjoffre.ledgerly.contas.aplicacao.ClienteService;
import io.github.alanjoffre.ledgerly.contas.aplicacao.ContaService;
import io.github.alanjoffre.ledgerly.contas.dominio.Cpf;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/clientes")
class ClienteController {

    private final ClienteService clientes;
    private final ContaService contas;

    ClienteController(ClienteService clientes, ContaService contas) {
        this.clientes = clientes;
        this.contas = contas;
    }

    @PostMapping
    ResponseEntity<ClienteResposta> cadastrar(@Valid @RequestBody CadastrarClienteRequisicao requisicao) {
        var cliente = clientes.cadastrar(requisicao.nome(), Cpf.de(requisicao.cpf()));
        return ResponseEntity.created(URI.create("/v1/clientes/" + cliente.id())).body(ClienteResposta.de(cliente));
    }

    @GetMapping("/{id}")
    ClienteResposta consultar(@PathVariable UUID id) {
        return ClienteResposta.de(clientes.consultar(id));
    }

    @GetMapping("/{id}/contas")
    List<ContaResposta> contas(@PathVariable UUID id) {
        return contas.listarDoCliente(id).stream().map(ContaResposta::de).toList();
    }
}
