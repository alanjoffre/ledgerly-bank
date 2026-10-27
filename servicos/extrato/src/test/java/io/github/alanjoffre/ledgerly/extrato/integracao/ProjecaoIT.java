package io.github.alanjoffre.ledgerly.extrato.integracao;

import static io.github.alanjoffre.ledgerly.extrato.integracao.MensagensDoContrato.CAIXA;
import static io.github.alanjoffre.ledgerly.extrato.integracao.MensagensDoContrato.LIQUIDACAO_PIX;
import static io.github.alanjoffre.ledgerly.extrato.integracao.MensagensDoContrato.credito;
import static io.github.alanjoffre.ledgerly.extrato.integracao.MensagensDoContrato.debito;
import static io.github.alanjoffre.ledgerly.extrato.integracao.MensagensDoContrato.envelope;
import static io.github.alanjoffre.ledgerly.extrato.integracao.MensagensDoContrato.lancamento;
import static io.github.alanjoffre.ledgerly.extrato.integracao.MensagensDoContrato.pixConcluido;
import static io.github.alanjoffre.ledgerly.extrato.integracao.MensagensDoContrato.pixEstornado;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.alanjoffre.ledgerly.extrato.aplicacao.porta.SaldoRepository;
import io.github.alanjoffre.ledgerly.extrato.dominio.Movimentacao;
import io.github.alanjoffre.ledgerly.extrato.dominio.Saldo;
import io.github.alanjoffre.ledgerly.extrato.dominio.SituacaoPix;
import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.LongStream;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.support.KafkaHeaders;

/** A projeção de ponta a ponta: mensagem no Kafka → consumidor → MongoDB → consulta. */
@DisplayName("Projeção dos eventos no extrato")
class ProjecaoIT extends TesteDeIntegracao {

    private static final Instant AGORA = Instant.parse("2026-10-27T18:30:00.123456Z");

    @Autowired
    private ConsumerFactory<String, String> consumidores;

    @Autowired
    private SaldoRepository saldos;

    @Autowired
    private MongoTemplate mongo;

    private void depositar(UUID lancamento, long sequencia, UUID conta, String valor, String saldoApos) {
        publicarLancamento(lancamento, lancamento(lancamento, "DEPOSITO", "salário", AGORA,
                debito(sequencia, CAIXA, valor, valor), credito(sequencia + 1, conta, valor, saldoApos)));
    }

    @Test
    @DisplayName("depósito aparece no extrato e no saldo; a conta interna Caixa fica de fora")
    void deposito() {
        UUID conta = UUID.randomUUID();
        UUID lancamento = UUID.randomUUID();
        depositar(lancamento, 10, conta, "100.00", "100.00");

        esperar(() -> assertThat(saldo(conta)).hasValueSatisfying(s -> assertThat(s.valor()).isEqualTo(Dinheiro.reais("100.00"))));
        Movimentacao linha = extrato(conta).itens().getFirst();
        assertThat(linha.lancamentoId()).isEqualTo(lancamento);
        assertThat(linha.descricao()).isEqualTo("Depósito");
        assertThat(linha.razao().mensagem()).isEqualTo("salário");
        assertThat(linha.razao().sequencia()).isEqualTo(11);
        // O razão mandou microssegundos; o MongoDB guarda milissegundos.
        assertThat(linha.razao().registradoEm()).isEqualTo(Instant.parse("2026-10-27T18:30:00.123Z"));
        assertThat(saldo(CAIXA)).isEmpty();
    }

    @Test
    @DisplayName("o mesmo evento entregue três vezes vira uma linha só (consumidor idempotente)")
    void duplicado() {
        UUID conta = UUID.randomUUID();
        UUID lancamento = UUID.randomUUID();
        String mensagem = lancamento(lancamento, "DEPOSITO", null, AGORA,
                debito(20, CAIXA, "50.00", "50.00"), credito(21, conta, "50.00", "50.00"));
        for (int i = 0; i < 3; i++) {
            publicarLancamento(lancamento, mensagem);
        }
        UUID marcador = UUID.randomUUID();
        // Mesma chave → mesma partição → este chega depois dos três: quando ele aparece, os três já foram.
        publicarLancamento(lancamento, lancamento(marcador, "DEPOSITO", null, AGORA,
                debito(22, CAIXA, "1.00", "51.00"), credito(23, conta, "1.00", "51.00")));

        esperar(() -> assertThat(extrato(conta).itens()).hasSize(2));
        assertThat(saldo(conta).orElseThrow().valor()).isEqualTo(Dinheiro.reais("51.00"));
    }

    @Test
    @DisplayName("evento atrasado não volta o saldo para trás: vale a partida de maior sequência")
    void foraDeOrdem() {
        UUID conta = UUID.randomUUID();
        UUID novo = UUID.randomUUID();
        UUID antigo = UUID.randomUUID();
        depositar(novo, 102, conta, "20.00", "30.00");   // chega primeiro, mas é o mais novo
        esperar(() -> assertThat(saldo(conta)).isPresent());
        depositar(antigo, 100, conta, "10.00", "10.00"); // chega depois, mas é o mais antigo

        esperar(() -> assertThat(extrato(conta).itens()).hasSize(2));
        assertThat(saldo(conta).orElseThrow().valor()).isEqualTo(Dinheiro.reais("30.00"));
        assertThat(saldo(conta).orElseThrow().sequencia()).isEqualTo(103);
        // O extrato sai na ordem do razão, não na ordem de chegada.
        assertThat(extrato(conta).itens()).extracting(m -> m.razao().sequencia()).containsExactly(103L, 101L);
    }

    @Test
    @DisplayName("Pix concluído antes do lançamento: a linha espera o razão e já nasce com os nomes")
    void pixAntesDoLancamento() {
        UUID joao = UUID.randomUUID();
        UUID maria = UUID.randomUUID();
        UUID pix = UUID.randomUUID();
        UUID envio = UUID.randomUUID();
        UUID recebimento = UUID.randomUUID();
        depositar(UUID.randomUUID(), 200, joao, "1500.00", "1500.00");

        publicarPix(pix, pixConcluido(pix, joao, "João da Silva", maria, "Maria Souza", envio, recebimento));
        // O pedaço do Pix já está gravado no MongoDB...
        esperar(() -> assertThat(mongo.getCollection("movimentacoes")
                .countDocuments(new Document("_id", recebimento + ":" + maria).append("pix.contraparte", "João da Silva")))
                .isEqualTo(1));
        // ...mas, sem o pedaço do razão, a linha não aparece: o extrato não mostra dinheiro que não sabe quanto é.
        assertThat(extrato(joao).itens()).hasSize(1);
        assertThat(saldo(maria)).isEmpty();

        publicarLancamento(envio, lancamento(envio, "PIX_ENVIO", "Aluguel", AGORA,
                debito(210, joao, "1200.00", "300.00"), credito(211, LIQUIDACAO_PIX, "1200.00", "1200.00")));
        publicarLancamento(recebimento, lancamento(recebimento, "PIX_RECEBIMENTO", "Aluguel", AGORA,
                debito(212, LIQUIDACAO_PIX, "1200.00", "0.00"), credito(213, maria, "1200.00", "1200.00")));

        esperar(() -> assertThat(extrato(maria).itens()).hasSize(1));
        esperar(() -> assertThat(extrato(joao).itens()).hasSize(2));
        Movimentacao enviado = extrato(joao).itens().getFirst();
        assertThat(enviado.descricao()).isEqualTo("Pix enviado para Maria Souza");
        assertThat(enviado.razao().mensagem()).isEqualTo("Aluguel");
        assertThat(enviado.pix().chave()).isEqualTo("m***@exemplo.com");
        assertThat(extrato(maria).itens().getFirst().descricao()).isEqualTo("Pix recebido de João da Silva");
        assertThat(saldo(joao).orElseThrow().valor()).isEqualTo(Dinheiro.reais("300.00"));
        assertThat(saldo(LIQUIDACAO_PIX)).isEmpty();
    }

    @Test
    @DisplayName("Pix estornado: o pagador vê a saída estornada e a volta do dinheiro com o motivo")
    void pixEstornadoDepoisDoLancamento() {
        UUID joao = UUID.randomUUID();
        UUID maria = UUID.randomUUID();
        UUID pix = UUID.randomUUID();
        UUID envio = UUID.randomUUID();
        UUID estorno = UUID.randomUUID();
        publicarLancamento(envio, lancamento(envio, "PIX_ENVIO", null, AGORA,
                debito(300, joao, "1200.00", "0.00"), credito(301, LIQUIDACAO_PIX, "1200.00", "1200.00")));
        publicarLancamento(estorno, lancamento(estorno, "PIX_ESTORNO", null, AGORA,
                debito(302, LIQUIDACAO_PIX, "1200.00", "0.00"), credito(303, joao, "1200.00", "1200.00")));
        esperar(() -> assertThat(extrato(joao).itens()).hasSize(2));
        assertThat(extrato(joao).itens().getFirst().descricao()).isEqualTo("Estorno de Pix");

        publicarPix(pix, pixEstornado(pix, joao, "João da Silva", maria, "Maria Souza", envio, estorno,
                "conta de destino encerrada"));

        esperar(() -> assertThat(extrato(joao).itens().getFirst().pix()).isNotNull());
        List<Movimentacao> linhas = extrato(joao).itens();
        assertThat(linhas.get(0).descricao()).isEqualTo("Estorno do Pix para Maria Souza: conta de destino encerrada");
        esperar(() -> assertThat(extrato(joao).itens().get(1).pix()).isNotNull());
        assertThat(extrato(joao).itens().get(1).pix().situacao()).isEqualTo(SituacaoPix.ESTORNADO);
        assertThat(saldo(joao).orElseThrow().valor()).isEqualTo(Dinheiro.reais("1200.00"));
        assertThat(saldo(maria)).isEmpty();
    }

    @Test
    @DisplayName("mensagem venenosa vai para a DLT e a fila não trava: o evento seguinte é processado")
    void mensagemVenenosa() {
        UUID chave = UUID.randomUUID();
        UUID conta = UUID.randomUUID();
        String semTipo = "{\"eventoId\":\"" + UUID.randomUUID() + "\",\"dados\":{}}";
        publicarLancamento(chave, "isto não é JSON");
        publicarLancamento(chave, semTipo);
        UUID depois = UUID.randomUUID();
        // Mesma chave → mesma partição, atrás das duas venenosas.
        publicarLancamento(chave, lancamento(depois, "DEPOSITO", null, AGORA,
                debito(400, CAIXA, "5.00", "5.00"), credito(401, conta, "5.00", "5.00")));

        esperar(() -> assertThat(saldo(conta)).isPresent());
        List<ConsumerRecord<String, String>> mortas = lerDlt(MensagensDoContrato.TOPICO_LANCAMENTOS + ".dlt", chave.toString(), 2);
        assertThat(mortas).extracting(ConsumerRecord::value).containsExactly("isto não é JSON", semTipo);
        assertThat(cabecalho(mortas.get(0), KafkaHeaders.DLT_EXCEPTION_MESSAGE)).contains("não é um JSON válido");
        assertThat(cabecalho(mortas.get(1), KafkaHeaders.DLT_EXCEPTION_MESSAGE)).contains("Campo 'tipo' ausente");
        assertThat(cabecalho(mortas.get(0), KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(MensagensDoContrato.TOPICO_LANCAMENTOS);
    }

    @Test
    @DisplayName("Pix com campo faltando vai para a DLT do tópico do Pix")
    void pixInvalido() {
        UUID pix = UUID.randomUUID();
        publicarPix(pix, envelope("PixConcluido", "{\"pixId\":\"" + pix + "\"}"));
        List<ConsumerRecord<String, String>> mortas = lerDlt(MensagensDoContrato.TOPICO_PIX + ".dlt", pix.toString(), 1);
        assertThat(cabecalho(mortas.getFirst(), KafkaHeaders.DLT_EXCEPTION_MESSAGE)).contains("Campo 'pagador' ausente");
    }

    @Test
    @DisplayName("tipo de evento desconhecido é ignorado, sem ir para a DLT")
    void tipoDesconhecido() {
        UUID conta = UUID.randomUUID();
        UUID chave = UUID.randomUUID();
        publicarLancamento(chave, envelope("LancamentoAnulado", "{\"lancamentoId\":\"" + chave + "\"}"));
        publicarPix(chave, envelope("PixAgendado", "{\"pixId\":\"" + chave + "\"}"));
        publicarLancamento(chave, lancamento(UUID.randomUUID(), "DEPOSITO", null, AGORA,
                debito(500, CAIXA, "7.00", "7.00"), credito(501, conta, "7.00", "7.00")));
        esperar(() -> assertThat(saldo(conta)).isPresent());
    }

    /**
     * Prova da atomicidade do upsert condicional, no pior caso: o saldo mais novo chega PRIMEIRO e
     * os mais velhos logo atrás, todos ao mesmo tempo (64 threads, 200 contas × 40 saldos). No fim,
     * cada conta tem de ficar com o de maior sequência.
     *
     * <p>Prova reversa: com "ler, comparar e gravar" em passos separados no lugar do upsert
     * condicional, este teste falha; e falhou também com o upsert condicional que aceitava a
     * primeira "chave duplicada" como resposta — números na ADR-0016.
     */
    @Test
    @DisplayName("8.000 saldos em paralelo, o mais novo chegando primeiro: cada conta fica com o de maior sequência")
    void saldoConcorrente() throws Exception {
        int contas = 200;
        long porConta = 40;
        List<UUID> ids = new ArrayList<>();
        List<Runnable> gravacoes = new ArrayList<>();
        Map<UUID, Boolean> gravouOMaisNovo = new ConcurrentHashMap<>();
        for (int c = 0; c < contas; c++) {
            UUID conta = UUID.randomUUID();
            ids.add(conta);
            for (long sequencia : LongStream.rangeClosed(1, porConta).map(s -> porConta + 1 - s).toArray()) {
                gravacoes.add(() -> {
                    boolean gravou = saldos.atualizarSeMaisNovo(new Saldo(conta, Dinheiro.reais(sequencia + ".00"), sequencia, AGORA));
                    if (sequencia == porConta) {
                        gravouOMaisNovo.put(conta, gravou);
                    }
                });
            }
        }
        try (var threads = Executors.newFixedThreadPool(64)) {
            gravacoes.forEach(threads::submit);
        }
        List<UUID> voltaramNoTempo = ids.stream()
                .filter(conta -> saldos.buscar(conta).orElseThrow().sequencia() != porConta)
                .toList();
        System.out.printf("MEDIDA saldo concorrente: %d de %d contas terminaram com saldo velho%n", voltaramNoTempo.size(), contas);
        assertThat(voltaramNoTempo).isEmpty();
        assertThat(gravouOMaisNovo).hasSize(contas).doesNotContainValue(false);
        UUID uma = ids.getFirst();
        assertThat(saldos.buscar(uma).orElseThrow().valor()).isEqualTo(Dinheiro.reais(porConta + ".00"));
        assertThat(saldos.atualizarSeMaisNovo(new Saldo(uma, Dinheiro.reais("1.00"), porConta, AGORA))).isFalse();
    }

    private List<ConsumerRecord<String, String>> lerDlt(String topico, String chave, int quantas) {
        List<ConsumerRecord<String, String>> achadas = new ArrayList<>();
        try (Consumer<String, String> leitor = consumidores.createConsumer("teste-dlt-" + UUID.randomUUID(), null)) {
            leitor.subscribe(List.of(topico));
            esperar(() -> {
                for (ConsumerRecord<String, String> registro : leitor.poll(Duration.ofMillis(200))) {
                    if (chave.equals(registro.key())) {
                        achadas.add(registro);
                    }
                }
                assertThat(achadas).hasSize(quantas);
            });
        }
        return achadas;
    }

    private static String cabecalho(ConsumerRecord<String, String> registro, String nome) {
        Header cabecalho = registro.headers().lastHeader(nome);
        return cabecalho == null ? null : new String(cabecalho.value(), StandardCharsets.UTF_8);
    }
}
