package io.github.alanjoffre.ledgerly.antifraude.aplicacao.porta;

import io.github.alanjoffre.ledgerly.antifraude.dominio.Avaliacao;
import io.github.alanjoffre.ledgerly.antifraude.dominio.Explicacao;
import io.github.alanjoffre.ledgerly.antifraude.dominio.HistoricoDoPagador;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** As avaliações gravadas — que também são o histórico de onde sai o costume de cada pagador. */
public interface AvaliacaoRepository {

    Optional<Avaliacao> buscar(UUID transferenciaId);

    /** O costume do pagador antes deste Pix: média dos últimos, quantos na última hora, se já pagou esse recebedor. */
    HistoricoDoPagador historico(UUID pagadorContaId, UUID recebedorContaId, Instant antesDe);

    /**
     * Grava a avaliação.
     *
     * @return {@code false} se a transferência já tinha uma (dois pedidos iguais ao mesmo tempo: só um grava)
     */
    boolean inserir(Avaliacao avaliacao);

    Optional<Explicacao> explicacao(UUID transferenciaId);

    /**
     * Grava a explicação, se a avaliação ainda não tem uma.
     *
     * @return {@code false} se outra leitura gravou antes (duas analistas abrindo o mesmo Pix)
     */
    boolean guardarExplicacao(UUID transferenciaId, Explicacao explicacao);
}
