package io.github.alanjoffre.ledgerly.extrato.aplicacao;

import java.util.UUID;

/**
 * Quem pede pode ver o extrato deste cliente? A autorização por objeto (ADR-0026), como no contas.
 *
 * <p>O extrato não sabe de quem é cada conta (ele se monta só com os lançamentos do razão). Para
 * o cliente, a consulta pergunta o dono ao contas ({@link porta.DonoDaConta}); para quem
 * {@link #leTudo() lê tudo} (analista, serviço), não pergunta nada.
 */
public interface PermissaoDeLeitura {

    /** Analista e serviço: leem qualquer conta, sem perguntar o dono ao contas. */
    PermissaoDeLeitura TOTAL = new PermissaoDeLeitura() {
        @Override
        public boolean podeVer(UUID clienteId) {
            return true;
        }

        @Override
        public boolean leTudo() {
            return true;
        }
    };

    boolean podeVer(UUID clienteId);

    default boolean leTudo() {
        return false;
    }
}
