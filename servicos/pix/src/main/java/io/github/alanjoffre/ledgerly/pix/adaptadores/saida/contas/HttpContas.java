package io.github.alanjoffre.ledgerly.pix.adaptadores.saida.contas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.alanjoffre.ledgerly.pix.aplicacao.ServicoIndisponivelException;
import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.Contas;
import io.github.alanjoffre.ledgerly.pix.dominio.ContaNaoEncontradaException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.Response;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Adaptador de saída: o serviço de contas. Se ele não responder, a operação do Pix para antes de
 * gravar qualquer coisa ({@link ServicoIndisponivelException} → 503).
 */
@ApplicationScoped
class HttpContas implements Contas {

    private final ContasCliente cliente;
    private final ObjectMapper json;

    HttpContas(@RestClient ContasCliente cliente, ObjectMapper json) {
        this.cliente = cliente;
        this.json = json;
    }

    @Override
    public Optional<ContaResumo> buscar(UUID contaId) {
        try (Response resposta = cliente.titular(contaId)) {
            if (resposta.getStatus() == 404) {
                return Optional.empty();
            }
            JsonNode corpo = exigirSucesso(resposta);
            return Optional.of(new ContaResumo(
                    contaId,
                    uuidOuNulo(corpo.path("clienteId").asText(null)),
                    corpo.path("nome").asText(),
                    "ATIVA".equals(corpo.path("situacao").asText())));
        } catch (ProcessingException e) {
            throw indisponivel(e);
        }
    }

    @Override
    public boolean titularidadeConfere(UUID contaId, String cpf) {
        try (Response resposta = cliente.titularidade(contaId, new ContasCliente.PedidoDeTitularidade(cpf))) {
            if (resposta.getStatus() == 404) {
                throw new ContaNaoEncontradaException("Conta %s não encontrada".formatted(contaId));
            }
            return exigirSucesso(resposta).path("confere").asBoolean(false);
        } catch (ProcessingException e) {
            throw indisponivel(e);
        }
    }

    private JsonNode exigirSucesso(Response resposta) {
        if (resposta.getStatus() != 200) {
            throw new ServicoIndisponivelException(
                    "O serviço de contas respondeu HTTP %d".formatted(resposta.getStatus()));
        }
        try {
            return json.readTree(resposta.readEntity(String.class));
        } catch (Exception e) {
            throw new ServicoIndisponivelException("Resposta ilegível do serviço de contas");
        }
    }

    private static UUID uuidOuNulo(String texto) {
        return texto == null ? null : UUID.fromString(texto);
    }

    private static ServicoIndisponivelException indisponivel(ProcessingException e) {
        return new ServicoIndisponivelException("O serviço de contas não respondeu: " + e.getMessage());
    }
}
