package io.github.alanjoffre.ledgerly.extrato.adaptadores.entrada.kafka;

import io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos.LancamentoRegistrado;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos.Pagador;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos.PartidaRegistrada;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos.PixConcluido;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos.PixEstornado;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos.Recebedor;
import io.github.alanjoffre.ledgerly.extrato.dominio.TipoOperacao;
import io.github.alanjoffre.ledgerly.extrato.dominio.TipoPartida;
import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lê as mensagens do Kafka segundo o contrato ({@code docs/arquitetura/eventos.md}) e entrega os
 * eventos da aplicação ({@link io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos}).
 *
 * <p>A leitura é feita campo a campo, de propósito: cada campo faltando ou malformado vira um
 * {@link EventoInvalidoException} que diz <i>qual</i> campo — é o que alguém vai ler na DLT para
 * descobrir o que o produtor mandou errado. Campos que o extrato não usa são ignorados: o produtor
 * pode acrescentar campos sem quebrar o consumidor.
 */
@Component
class LeitorDeEventos {

    /** O envelope comum a todo evento: o tipo e os dados. */
    record Envelope(String tipo, JsonNode dados) {}

    private final JsonMapper json;

    LeitorDeEventos(JsonMapper json) {
        this.json = json;
    }

    Envelope envelope(String mensagem) {
        if (mensagem == null) {
            throw new EventoInvalidoException("Mensagem vazia");
        }
        JsonNode raiz;
        try {
            raiz = json.readTree(mensagem);
        } catch (JacksonException e) {
            throw new EventoInvalidoException("A mensagem não é um JSON válido", e);
        }
        if (raiz == null || !raiz.isObject()) {
            throw new EventoInvalidoException("A mensagem não é um objeto JSON");
        }
        JsonNode dados = raiz.path("dados");
        if (!dados.isObject()) {
            throw new EventoInvalidoException("Campo 'dados' ausente ou não é um objeto");
        }
        return new Envelope(texto(raiz, "tipo"), dados);
    }

    LancamentoRegistrado lancamentoRegistrado(JsonNode dados) {
        return converter("LancamentoRegistrado", () -> {
            Currency moeda = moeda(dados);
            List<PartidaRegistrada> partidas = new ArrayList<>();
            for (JsonNode partida : lista(dados, "partidas")) {
                partidas.add(new PartidaRegistrada(
                        inteiro(partida, "sequencia"),
                        uuid(partida, "contaId"),
                        logico(partida, "contaInterna"),
                        enumeracao(partida, "tipo", TipoPartida::valueOf),
                        dinheiro(partida, "valor", moeda),
                        dinheiro(partida, "saldoApos", moeda)));
            }
            return new LancamentoRegistrado(
                    uuid(dados, "lancamentoId"),
                    enumeracao(dados, "tipo", TipoOperacao::valueOf),
                    textoOpcional(dados, "descricao"),
                    instante(dados, "registradoEm"),
                    partidas);
        });
    }

    PixConcluido pixConcluido(JsonNode dados) {
        return converter("PixConcluido", () -> new PixConcluido(
                uuid(dados, "pixId"),
                pagador(dados),
                recebedor(dados),
                uuid(dados, "lancamentoEnvioId"),
                uuid(dados, "lancamentoRecebimentoId")));
    }

    PixEstornado pixEstornado(JsonNode dados) {
        return converter("PixEstornado", () -> new PixEstornado(
                uuid(dados, "pixId"),
                pagador(dados),
                recebedor(dados),
                uuid(dados, "lancamentoEnvioId"),
                uuid(dados, "lancamentoEstornoId"),
                textoOpcional(dados, "motivo"),
                uuidOpcional(dados, "contaDevolucaoId")));
    }

    private static Pagador pagador(JsonNode dados) {
        JsonNode pagador = objeto(dados, "pagador");
        return new Pagador(uuid(pagador, "contaId"), texto(pagador, "nome"));
    }

    private static Recebedor recebedor(JsonNode dados) {
        JsonNode recebedor = objeto(dados, "recebedor");
        return new Recebedor(
                uuid(recebedor, "contaId"),
                texto(recebedor, "nome"),
                textoOpcional(recebedor, "tipoChave"),
                textoOpcional(recebedor, "chave"));
    }

    /** Qualquer erro de conversão (valor com casas demais, enum desconhecido...) vira evento inválido. */
    private static <T> T converter(String tipo, Supplier<T> conversao) {
        try {
            return conversao.get();
        } catch (EventoInvalidoException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new EventoInvalidoException("Evento %s inválido: %s".formatted(tipo, e.getMessage()), e);
        }
    }

    // ---- Campos ----

    private static JsonNode campo(JsonNode no, String nome) {
        JsonNode valor = no.path(nome);
        if (valor.isMissingNode() || valor.isNull()) {
            throw new EventoInvalidoException("Campo '%s' ausente".formatted(nome));
        }
        return valor;
    }

    private static String texto(JsonNode no, String nome) {
        JsonNode valor = campo(no, nome);
        if (!valor.isString() || valor.stringValue().isBlank()) {
            throw new EventoInvalidoException("Campo '%s' deve ser um texto não vazio".formatted(nome));
        }
        return valor.stringValue();
    }

    private static String textoOpcional(JsonNode no, String nome) {
        JsonNode valor = no.path(nome);
        if (valor.isMissingNode() || valor.isNull()) {
            return null;
        }
        if (!valor.isString()) {
            throw new EventoInvalidoException("Campo '%s' deve ser um texto".formatted(nome));
        }
        return valor.stringValue();
    }

    private static JsonNode objeto(JsonNode no, String nome) {
        JsonNode valor = campo(no, nome);
        if (!valor.isObject()) {
            throw new EventoInvalidoException("Campo '%s' deve ser um objeto".formatted(nome));
        }
        return valor;
    }

    private static Iterable<JsonNode> lista(JsonNode no, String nome) {
        JsonNode valor = campo(no, nome);
        if (!valor.isArray()) {
            throw new EventoInvalidoException("Campo '%s' deve ser uma lista".formatted(nome));
        }
        return valor.values();
    }

    private static long inteiro(JsonNode no, String nome) {
        JsonNode valor = campo(no, nome);
        if (!valor.isIntegralNumber() || !valor.canConvertToLong()) {
            throw new EventoInvalidoException("Campo '%s' deve ser um número inteiro".formatted(nome));
        }
        return valor.longValue();
    }

    private static boolean logico(JsonNode no, String nome) {
        JsonNode valor = campo(no, nome);
        if (!valor.isBoolean()) {
            throw new EventoInvalidoException("Campo '%s' deve ser true ou false".formatted(nome));
        }
        return valor.booleanValue();
    }

    private static UUID uuid(JsonNode no, String nome) {
        String valor = texto(no, nome);
        try {
            return UUID.fromString(valor);
        } catch (IllegalArgumentException e) {
            throw new EventoInvalidoException("Campo '%s' não é um UUID: %s".formatted(nome, valor), e);
        }
    }

    private static UUID uuidOpcional(JsonNode no, String nome) {
        return textoOpcional(no, nome) == null ? null : uuid(no, nome);
    }

    private static Instant instante(JsonNode no, String nome) {
        String valor = texto(no, nome);
        try {
            return Instant.parse(valor);
        } catch (DateTimeParseException e) {
            throw new EventoInvalidoException("Campo '%s' não é uma data ISO-8601: %s".formatted(nome, valor), e);
        }
    }

    private static Currency moeda(JsonNode no) {
        String valor = texto(no, "moeda");
        try {
            return Currency.getInstance(valor);
        } catch (IllegalArgumentException e) {
            throw new EventoInvalidoException("Campo 'moeda' não é um código ISO 4217: " + valor, e);
        }
    }

    /** Dinheiro chega como texto ("10.50"), nunca como número JSON (ADR-0012). */
    private static Dinheiro dinheiro(JsonNode no, String nome, Currency moeda) {
        String valor = texto(no, nome);
        try {
            return Dinheiro.de(new BigDecimal(valor), moeda);
        } catch (NumberFormatException e) {
            throw new EventoInvalidoException("Campo '%s' não é um valor como \"10.50\": %s".formatted(nome, valor), e);
        }
    }

    private static <E extends Enum<E>> E enumeracao(JsonNode no, String nome, Function<String, E> conversao) {
        String valor = texto(no, nome);
        try {
            return conversao.apply(valor);
        } catch (IllegalArgumentException e) {
            throw new EventoInvalidoException("Campo '%s' tem valor desconhecido: %s".formatted(nome, valor), e);
        }
    }
}
