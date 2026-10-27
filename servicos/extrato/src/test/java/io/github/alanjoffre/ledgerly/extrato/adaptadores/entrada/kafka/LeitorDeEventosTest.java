package io.github.alanjoffre.ledgerly.extrato.adaptadores.entrada.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos.LancamentoRegistrado;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos.PixConcluido;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.Eventos.PixEstornado;
import io.github.alanjoffre.ledgerly.extrato.dominio.TipoOperacao;
import io.github.alanjoffre.ledgerly.extrato.dominio.TipoPartida;
import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/** O leitor do contrato: os exemplos do docs/arquitetura/eventos.md e cada forma de mensagem errada. */
@DisplayName("Leitura das mensagens do contrato")
class LeitorDeEventosTest {

    private final LeitorDeEventos leitor = new LeitorDeEventos(JsonMapper.builder().build());

    private static final String PARTIDAS = """
            [{"sequencia":1041,"contaId":"c1c1c1c1-0000-4000-8000-000000000001","contaInterna":false,"tipo":"DEBITO","valor":"1200.00","saldoApos":"300.00"},
             {"sequencia":1042,"contaId":"00000000-0000-0000-0000-000000000002","contaInterna":true,"tipo":"CREDITO","valor":"1200.00","saldoApos":"1200.00"}]""";

    private static final String LANCAMENTO = """
            {"eventoId":"3f1c9a52-7d1e-4b8a-9c0e-2a6b5f4d8e10","tipo":"LancamentoRegistrado","versao":1,
             "ocorridoEm":"2026-10-27T18:30:00.123456Z",
             "dados":{"lancamentoId":"9b2e0f6a-1c3d-4e5f-8a9b-0c1d2e3f4a5b","tipo":"PIX_ENVIO","descricao":"Aluguel",
               "moeda":"BRL","registradoEm":"2026-10-27T18:30:00.123456Z","campoNovo":"ignorado",
               "partidas":%s}}""".formatted(PARTIDAS);

    private static final String PIX_CONCLUIDO = """
            {"tipo":"PixConcluido","dados":{"pixId":"5d0a0000-0000-4000-8000-000000000001","valor":"1200.00","moeda":"BRL",
              "pagador":{"contaId":"c1c1c1c1-0000-4000-8000-000000000001","nome":"João da Silva"},
              "recebedor":{"contaId":"c2c2c2c2-0000-4000-8000-000000000002","nome":"Maria Souza","tipoChave":"EMAIL","chave":"m***@exemplo.com"},
              "lancamentoEnvioId":"9b2e0f6a-1c3d-4e5f-8a9b-0c1d2e3f4a5b","lancamentoRecebimentoId":"a7c40000-0000-4000-8000-000000000003"}}""";

    @Test
    @DisplayName("LancamentoRegistrado do contrato: campos, tipos e campo desconhecido ignorado")
    void lancamento() {
        var envelope = leitor.envelope(LANCAMENTO);
        assertThat(envelope.tipo()).isEqualTo("LancamentoRegistrado");
        LancamentoRegistrado evento = leitor.lancamentoRegistrado(envelope.dados());
        assertThat(evento.tipo()).isEqualTo(TipoOperacao.PIX_ENVIO);
        assertThat(evento.descricao()).isEqualTo("Aluguel");
        assertThat(evento.registradoEm()).isEqualTo(Instant.parse("2026-10-27T18:30:00.123456Z"));
        assertThat(evento.partidas()).hasSize(2);
        var primeira = evento.partidas().getFirst();
        assertThat(primeira.sequencia()).isEqualTo(1041);
        assertThat(primeira.tipo()).isEqualTo(TipoPartida.DEBITO);
        assertThat(primeira.valor()).isEqualTo(Dinheiro.reais("1200.00"));
        assertThat(primeira.saldoApos()).isEqualTo(Dinheiro.reais("300.00"));
        assertThat(primeira.contaInterna()).isFalse();
        assertThat(evento.partidas().get(1).contaInterna()).isTrue();
    }

    @Test
    @DisplayName("PixConcluido do contrato")
    void pixConcluido() {
        PixConcluido evento = leitor.pixConcluido(leitor.envelope(PIX_CONCLUIDO).dados());
        assertThat(evento.pagador().nome()).isEqualTo("João da Silva");
        assertThat(evento.recebedor().nome()).isEqualTo("Maria Souza");
        assertThat(evento.recebedor().chave()).isEqualTo("m***@exemplo.com");
        assertThat(evento.lancamentoRecebimentoId()).isEqualTo(UUID.fromString("a7c40000-0000-4000-8000-000000000003"));
    }

    @Test
    @DisplayName("PixEstornado: motivo e lançamento do estorno; tipo e valor da chave são opcionais")
    void pixEstornado() {
        PixEstornado evento = leitor.pixEstornado(leitor.envelope("""
                {"tipo":"PixEstornado","dados":{"pixId":"5d0a0000-0000-4000-8000-000000000001",
                  "pagador":{"contaId":"c1c1c1c1-0000-4000-8000-000000000001","nome":"João"},
                  "recebedor":{"contaId":"c2c2c2c2-0000-4000-8000-000000000002","nome":"Maria","chave":null},
                  "lancamentoEnvioId":"9b2e0f6a-1c3d-4e5f-8a9b-0c1d2e3f4a5b",
                  "lancamentoEstornoId":"e5e50000-0000-4000-8000-000000000004","motivo":"conta de destino encerrada"}}""").dados());
        assertThat(evento.motivo()).isEqualTo("conta de destino encerrada");
        assertThat(evento.recebedor().chave()).isNull();
        assertThat(evento.recebedor().tipoChave()).isNull();
    }

    static Stream<Arguments> envelopesInvalidos() {
        return Stream.of(
                arguments("não é JSON", "isto não é json", "não é um JSON válido"),
                arguments("JSON que não é objeto", "[1,2]", "não é um objeto JSON"),
                arguments("sem dados", "{\"tipo\":\"X\"}", "'dados' ausente"),
                arguments("dados não é objeto", "{\"tipo\":\"X\",\"dados\":1}", "'dados' ausente"),
                arguments("sem tipo", "{\"dados\":{}}", "'tipo' ausente"),
                arguments("tipo vazio", "{\"tipo\":\" \",\"dados\":{}}", "'tipo' deve ser um texto"),
                arguments("tipo número", "{\"tipo\":3,\"dados\":{}}", "'tipo' deve ser um texto"));
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("envelope fora do contrato → EventoInvalidoException dizendo o que está errado")
    @MethodSource("envelopesInvalidos")
    void envelopeInvalido(String caso, String mensagem, String erro) {
        assertThatThrownBy(() -> leitor.envelope(mensagem)).isInstanceOf(EventoInvalidoException.class).hasMessageContaining(erro);
    }

    @Test
    @DisplayName("mensagem nula (tombstone do Kafka) é inválida")
    void nula() {
        assertThatThrownBy(() -> leitor.envelope(null)).isInstanceOf(EventoInvalidoException.class).hasMessageContaining("vazia");
    }

    static Stream<Arguments> lancamentosInvalidos() {
        return Stream.of(
                arguments("UUID inválido", "\"lancamentoId\":\"9b2e0f6a-1c3d-4e5f-8a9b-0c1d2e3f4a5b\"", "\"lancamentoId\":\"abc\"",
                        "'lancamentoId' não é um UUID"),
                arguments("tipo desconhecido", "\"tipo\":\"PIX_ENVIO\"", "\"tipo\":\"BOLETO\"", "'tipo' tem valor desconhecido"),
                arguments("data inválida", "\"registradoEm\":\"2026-10-27T18:30:00.123456Z\"", "\"registradoEm\":\"ontem\"",
                        "'registradoEm' não é uma data"),
                arguments("moeda inválida", "\"moeda\":\"BRL\"", "\"moeda\":\"REAL\"", "'moeda' não é um código ISO 4217"),
                arguments("valor como número", "\"valor\":\"1200.00\"", "\"valor\":1200", "'valor' deve ser um texto"),
                arguments("valor sem formato", "\"valor\":\"1200.00\"", "\"valor\":\"mil\"", "'valor' não é um valor"),
                arguments("casas demais", "\"valor\":\"1200.00\"", "\"valor\":\"1.001\"", "mais de 2 casas"),
                arguments("sequência em texto", "\"sequencia\":1041", "\"sequencia\":\"1041\"", "'sequencia' deve ser um número inteiro"),
                arguments("contaInterna em texto", "\"contaInterna\":false", "\"contaInterna\":\"nao\"", "'contaInterna' deve ser true ou false"),
                arguments("partidas não é lista", PARTIDAS, "{}", "'partidas' deve ser uma lista"),
                arguments("descrição número", "\"descricao\":\"Aluguel\"", "\"descricao\":7", "'descricao' deve ser um texto"),
                arguments("tipo da partida desconhecido", "\"tipo\":\"DEBITO\"", "\"tipo\":\"SAIDA\"", "'tipo' tem valor desconhecido"));
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("lançamento com campo errado → EventoInvalidoException com o nome do campo")
    @MethodSource("lancamentosInvalidos")
    void lancamentoInvalido(String caso, String trecho, String troca, String erro) {
        assertThat(LANCAMENTO).contains(trecho);
        String alterada = LANCAMENTO.replace(trecho, troca);
        assertThatThrownBy(() -> leitor.lancamentoRegistrado(leitor.envelope(alterada).dados()))
                .isInstanceOf(EventoInvalidoException.class).hasMessageContaining(erro);
    }

    @Test
    @DisplayName("Pix com pagador que não é objeto, ou nome nulo → inválido")
    void pixInvalido() {
        assertThatThrownBy(() -> leitor.pixConcluido(leitor.envelope(PIX_CONCLUIDO.replace(
                "\"pagador\":{\"contaId\":\"c1c1c1c1-0000-4000-8000-000000000001\",\"nome\":\"João da Silva\"}",
                "\"pagador\":\"João\"")).dados()))
                .isInstanceOf(EventoInvalidoException.class).hasMessageContaining("'pagador' deve ser um objeto");
        assertThatThrownBy(() -> leitor.pixConcluido(leitor.envelope(PIX_CONCLUIDO.replace(
                "\"nome\":\"Maria Souza\"", "\"nome\":null")).dados()))
                .isInstanceOf(EventoInvalidoException.class).hasMessageContaining("'nome' ausente");
        assertThatThrownBy(() -> leitor.pixConcluido(leitor.envelope(PIX_CONCLUIDO.replace(
                "\"chave\":\"m***@exemplo.com\"", "\"chave\":5")).dados()))
                .isInstanceOf(EventoInvalidoException.class).hasMessageContaining("'chave' deve ser um texto");
    }
}
