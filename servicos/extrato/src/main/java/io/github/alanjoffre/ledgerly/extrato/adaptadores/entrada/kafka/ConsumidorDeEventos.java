package io.github.alanjoffre.ledgerly.extrato.adaptadores.entrada.kafka;

import io.github.alanjoffre.ledgerly.extrato.aplicacao.ProjecaoDoExtrato;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Os consumidores do Kafka: leem os dois tópicos e entregam cada evento à projeção.
 *
 * <p><b>Grupo de consumidores</b> ({@code extrato}): as partições de cada tópico são divididas
 * entre as instâncias do grupo, e o Kafka guarda até onde o grupo já leu (o <i>offset</i>). O
 * offset só avança depois que o evento foi gravado no MongoDB: se o serviço cair antes, o evento é
 * entregue de novo — e a projeção é idempotente, então tanto faz.
 *
 * <p>Tipo de evento desconhecido é <b>ignorado</b>, não é erro: o produtor pode publicar um tipo
 * novo no tópico antes de o extrato precisar dele.
 */
@Component
class ConsumidorDeEventos {

    static final String TOPICO_LANCAMENTOS = "razao.lancamentos.v1";
    static final String TOPICO_PIX = "pix.transferencias.v1";

    /** Ids dos consumidores: é por eles que se para e religa a leitura (na reconstrução, por exemplo). */
    static final String CONSUMIDOR_LANCAMENTOS = "extrato-lancamentos";
    static final String CONSUMIDOR_PIX = "extrato-pix";

    private static final Logger LOG = LoggerFactory.getLogger(ConsumidorDeEventos.class);

    private final LeitorDeEventos leitor;
    private final ProjecaoDoExtrato projecao;

    ConsumidorDeEventos(LeitorDeEventos leitor, ProjecaoDoExtrato projecao) {
        this.leitor = leitor;
        this.projecao = projecao;
    }

    // idIsGroup = false: o id nomeia o consumidor; o grupo continua sendo "extrato" (configuração).
    @KafkaListener(id = CONSUMIDOR_LANCAMENTOS, idIsGroup = false, topics = TOPICO_LANCAMENTOS)
    void lancamento(String mensagem) {
        var envelope = leitor.envelope(mensagem);
        if ("LancamentoRegistrado".equals(envelope.tipo())) {
            projecao.projetar(leitor.lancamentoRegistrado(envelope.dados()));
        } else {
            ignorar(TOPICO_LANCAMENTOS, envelope.tipo());
        }
    }

    @KafkaListener(id = CONSUMIDOR_PIX, idIsGroup = false, topics = TOPICO_PIX)
    void pix(String mensagem) {
        var envelope = leitor.envelope(mensagem);
        switch (envelope.tipo()) {
            case "PixConcluido" -> projecao.projetar(leitor.pixConcluido(envelope.dados()));
            case "PixEstornado" -> projecao.projetar(leitor.pixEstornado(envelope.dados()));
            default -> ignorar(TOPICO_PIX, envelope.tipo());
        }
    }

    private static void ignorar(String topico, String tipo) {
        LOG.info("Evento {} no tópico {} ignorado: o extrato não usa este tipo", tipo, topico);
    }
}
