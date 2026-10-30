package io.github.alanjoffre.ledgerly.antifraude.aplicacao;

import io.github.alanjoffre.ledgerly.antifraude.aplicacao.porta.AvaliacaoRepository;
import io.github.alanjoffre.ledgerly.antifraude.aplicacao.porta.Redator;
import io.github.alanjoffre.ledgerly.antifraude.dominio.Avaliacao;
import io.github.alanjoffre.ledgerly.antifraude.dominio.Decisao;
import io.github.alanjoffre.ledgerly.antifraude.dominio.Explicacao;
import io.github.alanjoffre.ledgerly.antifraude.dominio.GuardrailDeNumeros;
import jakarta.enterprise.context.ApplicationScoped;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * A explicação de uma avaliação para a analista (ADR-0035): o modelo de linguagem escreve, o
 * {@link GuardrailDeNumeros} confere, e o texto fixo cobre quando ele falha.
 *
 * <ul>
 *   <li>Feita uma vez por avaliação, na primeira leitura, e gravada: a analista que abrir o Pix
 *       depois lê o mesmo texto, e o modelo de linguagem não é chamado de novo.</li>
 *   <li>Pix aprovado não chama o modelo de linguagem: ninguém do backoffice precisa ler por que um
 *       Pix normal seguiu, e o texto fixo basta.</li>
 *   <li>Texto que inventou número: descartado, e o texto fixo é gravado no lugar.</li>
 *   <li>Modelo de linguagem fora do ar ou lento: o texto fixo vai na resposta, mas <b>não</b> é
 *       gravado — a próxima leitura tenta de novo.</li>
 * </ul>
 *
 * <p>Sem transação em volta da chamada ao modelo de linguagem: ela leva segundos, e uma conexão do
 * banco presa esse tempo todo faria falta para o POST do Pix.
 */
@ApplicationScoped
public class ExplicacaoService {

    private static final System.Logger LOG = System.getLogger(ExplicacaoService.class.getName());

    private final AvaliacaoRepository avaliacoes;
    private final Redator redator;
    private final Clock relogio;

    public ExplicacaoService(AvaliacaoRepository avaliacoes, Redator redator, Clock relogio) {
        this.avaliacoes = avaliacoes;
        this.redator = redator;
        this.relogio = relogio;
    }

    /** @return vazio se a transferência não tem avaliação */
    public Optional<Explicacao> explicar(UUID transferenciaId) {
        Optional<Avaliacao> avaliacao = avaliacoes.buscar(transferenciaId);
        if (avaliacao.isEmpty()) {
            return Optional.empty();
        }
        Optional<Explicacao> pronta = avaliacoes.explicacao(transferenciaId);
        if (pronta.isPresent()) {
            return pronta;
        }
        Avaliacao a = avaliacao.get();
        if (a.decisao() == Decisao.APROVAR) {
            return Optional.of(guardar(transferenciaId, Explicacao.textoFixo(a, agora())));
        }
        Optional<String> texto = redator.redigir(a);
        if (texto.isEmpty()) {
            return Optional.of(Explicacao.textoFixo(a, agora()));
        }
        Optional<String> problema = GuardrailDeNumeros.problema(texto.get(), a);
        if (problema.isPresent()) {
            LOG.log(Level.WARNING, "Explicação de {0} descartada ({1}): {2}", transferenciaId, problema.get(), texto.get());
            return Optional.of(guardar(transferenciaId, Explicacao.textoFixo(a, agora())));
        }
        return Optional.of(guardar(transferenciaId,
                new Explicacao(texto.get().strip(), Explicacao.Origem.MODELO_DE_LINGUAGEM, redator.modelo(), agora())));
    }

    /** Duas analistas ao mesmo tempo: vale o texto de quem gravou primeiro, para as duas. */
    private Explicacao guardar(UUID transferenciaId, Explicacao explicacao) {
        return avaliacoes.guardarExplicacao(transferenciaId, explicacao)
                ? explicacao
                : avaliacoes.explicacao(transferenciaId).orElseThrow();
    }

    private Instant agora() {
        return relogio.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
