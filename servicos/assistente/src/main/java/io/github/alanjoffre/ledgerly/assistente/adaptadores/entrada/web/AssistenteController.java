package io.github.alanjoffre.ledgerly.assistente.adaptadores.entrada.web;

import io.github.alanjoffre.ledgerly.assistente.aplicacao.AssistenteService;
import io.github.alanjoffre.ledgerly.assistente.aplicacao.Cliente;
import io.github.alanjoffre.ledgerly.assistente.dominio.Resposta;
import io.github.alanjoffre.ledgerly.seguranca.AutenticacaoLedgerly;
import io.github.alanjoffre.ledgerly.seguranca.Papeis;
import io.github.alanjoffre.ledgerly.seguranca.Usuario;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * A pergunta ao assistente. Só o cliente pergunta, e sobre as próprias contas: o token dele segue
 * para o contas e o extrato (ADR-0036).
 */
@RestController
@RequestMapping("/v1/assistente")
class AssistenteController {

    private final AssistenteService assistente;

    AssistenteController(AssistenteService assistente) {
        this.assistente = assistente;
    }

    record PerguntaRequisicao(@NotNull String pergunta) {}

    record RespostaJson(String texto, Resposta.Origem origem, List<String> fontes) {}

    @PostMapping("/perguntas")
    RespostaJson perguntar(@Valid @RequestBody PerguntaRequisicao requisicao, AutenticacaoLedgerly autenticacao) {
        Usuario usuario = autenticacao.getPrincipal();
        var clienteId = usuario.clienteId()
                .filter(id -> usuario.tem(Papeis.CLIENTE))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Só o cliente pergunta ao assistente"));
        Resposta resposta = assistente.responder(
                new Cliente(clienteId, autenticacao.getCredentials().getTokenValue()), requisicao.pergunta());
        return new RespostaJson(resposta.texto(), resposta.origem(), resposta.fontes());
    }
}
