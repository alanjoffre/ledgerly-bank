package io.github.alanjoffre.ledgerly.pix.aplicacao.porta;

import io.github.alanjoffre.ledgerly.pix.dominio.DecisaoDeAnalise;
import java.util.List;
import java.util.UUID;

/** Porta de saída: o registro das decisões de análise. Só acrescenta. */
public interface AuditoriaDeAnalise {

    void registrar(DecisaoDeAnalise decisao);

    List<DecisaoDeAnalise> daTransferencia(UUID transferenciaId);
}
