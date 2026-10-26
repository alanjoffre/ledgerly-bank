package io.github.alanjoffre.ledgerly.razao.adaptadores.entrada.web;

import io.github.alanjoffre.ledgerly.razao.adaptadores.entrada.web.Dtos.IntegridadeResposta;
import io.github.alanjoffre.ledgerly.razao.aplicacao.ContaRazaoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Conferência do razão inteiro (conciliação interna). Na operação real, um monitor chamaria isto
 * periodicamente e abriria um alerta se {@code integro} viesse {@code false}.
 */
@RestController
class IntegridadeController {

    private final ContaRazaoService servico;

    IntegridadeController(ContaRazaoService servico) {
        this.servico = servico;
    }

    @GetMapping("/v1/integridade")
    IntegridadeResposta verificar() {
        return IntegridadeResposta.de(servico.verificarIntegridade());
    }
}
