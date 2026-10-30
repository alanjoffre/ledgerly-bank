package io.github.alanjoffre.ledgerly.antifraude.aplicacao;

import io.github.alanjoffre.ledgerly.antifraude.aplicacao.porta.AvaliacaoRepository;
import io.github.alanjoffre.ledgerly.antifraude.aplicacao.porta.ModeloDeRisco;
import io.github.alanjoffre.ledgerly.antifraude.dominio.Avaliacao;
import io.github.alanjoffre.ledgerly.antifraude.dominio.FatosDoPix;
import io.github.alanjoffre.ledgerly.antifraude.dominio.PedidoDeAvaliacao;
import io.github.alanjoffre.ledgerly.antifraude.dominio.PoliticaDeRisco;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Avalia o risco de um Pix: junta o histórico do pagador, pergunta ao modelo, aplica as regras e
 * grava.
 *
 * <p><b>Idempotente</b> pela transferência: a Saga do pix repete o pedido quando não tem resposta,
 * e a segunda pergunta recebe a mesma avaliação, não uma nova (que já contaria a primeira no
 * histórico do pagador e daria outra nota).
 */
@ApplicationScoped
public class AvaliacaoService {

    private final AvaliacaoRepository avaliacoes;
    private final ModeloDeRisco modelo;
    private final Clock relogio;
    private final PoliticaDeRisco politica = new PoliticaDeRisco();

    public AvaliacaoService(AvaliacaoRepository avaliacoes, ModeloDeRisco modelo, Clock relogio) {
        this.avaliacoes = avaliacoes;
        this.modelo = modelo;
        this.relogio = relogio;
    }

    @Transactional
    public Avaliacao avaliar(PedidoDeAvaliacao pedido) {
        Optional<Avaliacao> anterior = avaliacoes.buscar(pedido.transferenciaId());
        if (anterior.isPresent()) {
            return anterior.get();
        }
        var historico = avaliacoes.historico(pedido.pagadorContaId(), pedido.recebedorContaId(), pedido.instante());
        FatosDoPix fatos = FatosDoPix.de(pedido, historico);
        Avaliacao avaliacao = politica.avaliar(pedido, fatos, modelo.pontuar(fatos), agora());
        if (!avaliacoes.inserir(avaliacao)) {
            // Corrida: o mesmo Pix avaliado ao mesmo tempo. Vale a que gravou primeiro.
            return avaliacoes.buscar(pedido.transferenciaId()).orElseThrow();
        }
        return avaliacao;
    }

    /** Em microssegundos, a precisão do PostgreSQL: a resposta da 1ª vez é igual à da repetição. */
    private Instant agora() {
        return relogio.instant().truncatedTo(ChronoUnit.MICROS);
    }

    public Optional<Avaliacao> consultar(UUID transferenciaId) {
        return avaliacoes.buscar(transferenciaId);
    }
}
