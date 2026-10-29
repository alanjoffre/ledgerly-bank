package io.github.alanjoffre.ledgerly.contas.adaptadores.entrada.web;

import io.github.alanjoffre.ledgerly.contas.aplicacao.PermissaoDeLeitura;
import io.github.alanjoffre.ledgerly.seguranca.Usuario;

/** Do token para a pergunta do caso de uso: quem pede pode ver os dados deste cliente? (ADR-0026) */
final class Permissoes {

    private Permissoes() {}

    /**
     * Serviço e analista leem qualquer cliente: o serviço porque a pessoa já foi conferida na ponta
     * (o pix confere o dono antes de chamar), o analista porque é o trabalho dele. O cliente lê só o
     * próprio {@code cliente_id}.
     */
    static PermissaoDeLeitura de(Usuario usuario) {
        if (usuario.ehServico() || usuario.ehAnalista()) {
            return PermissaoDeLeitura.TOTAL;
        }
        return usuario::ehDono;
    }
}
