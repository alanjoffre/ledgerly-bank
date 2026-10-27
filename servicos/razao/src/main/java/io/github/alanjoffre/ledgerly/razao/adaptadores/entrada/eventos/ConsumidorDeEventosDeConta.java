package io.github.alanjoffre.ledgerly.razao.adaptadores.entrada.eventos;

import io.github.alanjoffre.ledgerly.razao.aplicacao.ContaRazaoService;
import io.github.alanjoffre.ledgerly.razao.dominio.ContaRazao;
import io.github.alanjoffre.ledgerly.razao.dominio.SituacaoConta;
import java.util.Currency;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adaptador de entrada: os eventos do serviço de contas ({@code contas.eventos.v1}), que mantêm a
 * conta do razão igual à do cadastro (ADR-0014).
 *
 * <p><b>Idempotente</b>, porque a entrega é "pelo menos uma vez" (ADR-0013): abrir uma conta que já
 * existe não faz nada, e pôr a situação que a conta já tem também não.
 *
 * <p><b>Ordem:</b> a chave da mensagem é o id da conta, então os eventos de uma mesma conta chegam
 * na ordem em que aconteceram. A exceção é o encerramento, que é síncrono e pode chegar ao razão
 * antes de um bloqueio ainda na fila. Como o encerramento é definitivo, todo evento que encontra a
 * conta já encerrada é descartado: é um evento antigo, ultrapassado.
 *
 * <p>Erro que não se resolve tentando de novo (JSON inválido, regra de negócio) vai direto para o
 * tópico {@code contas.eventos.v1.dlt}; os outros, depois de algumas tentativas
 * ({@link ConfiguracaoConsumidores}).
 */
@Component
class ConsumidorDeEventosDeConta {

    static final String TOPICO = "contas.eventos.v1";

    private static final Logger LOG = LoggerFactory.getLogger(ConsumidorDeEventosDeConta.class);

    private final ContaRazaoService contas;
    private final JsonMapper json;

    ConsumidorDeEventosDeConta(ContaRazaoService contas, JsonMapper json) {
        this.contas = contas;
        this.json = json;
    }

    @KafkaListener(topics = TOPICO, groupId = "${razao.kafka.grupo:razao}")
    void consumir(ConsumerRecord<String, String> registro) {
        JsonNode envelope = ler(registro.value());
        String tipo = texto(envelope, "tipo");
        JsonNode dados = envelope.path("dados");
        UUID contaId = uuid(dados, "contaId");

        switch (tipo) {
            case "ContaAberta" -> contas.abrir(contaId, moeda(dados));
            case "ContaBloqueada" -> mudarSituacao(contaId, SituacaoConta.BLOQUEADA);
            case "ContaDesbloqueada" -> mudarSituacao(contaId, SituacaoConta.ATIVA);
            case "ContaEncerrada" -> mudarSituacao(contaId, SituacaoConta.ENCERRADA);
            // Tipo novo que este razão ainda não conhece: ignora, em vez de travar a fila.
            // É o que deixa o serviço de contas publicar um evento novo sem esperar o razão.
            default -> LOG.info("Evento de conta ignorado (tipo desconhecido): {}", tipo);
        }
    }

    private void mudarSituacao(UUID contaId, SituacaoConta nova) {
        ContaRazao atual = contas.consultar(contaId);
        if (atual.situacao() == SituacaoConta.ENCERRADA) {
            LOG.info("Evento para a conta {} descartado: ela já está encerrada", contaId);
            return;
        }
        contas.alterarSituacao(contaId, nova);
    }

    private JsonNode ler(String valor) {
        if (valor == null) {
            throw new EventoInvalidoException("Mensagem sem corpo");
        }
        try {
            return json.readTree(valor);
        } catch (JacksonException e) {
            throw new EventoInvalidoException("O corpo não é um JSON válido: " + e.getOriginalMessage());
        }
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode valor = no.path(campo);
        if (!valor.isString() || valor.asString().isBlank()) {
            throw new EventoInvalidoException("O campo '%s' é obrigatório".formatted(campo));
        }
        return valor.asString();
    }

    private static UUID uuid(JsonNode no, String campo) {
        try {
            return UUID.fromString(texto(no, campo));
        } catch (IllegalArgumentException e) {
            throw new EventoInvalidoException("O campo '%s' não é um UUID".formatted(campo));
        }
    }

    private static Currency moeda(JsonNode dados) {
        try {
            return Currency.getInstance(texto(dados, "moeda"));
        } catch (IllegalArgumentException e) {
            throw new EventoInvalidoException("O campo 'moeda' não é um código ISO 4217");
        }
    }
}
