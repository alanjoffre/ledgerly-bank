package io.github.alanjoffre.ledgerly.pix.dominio;

import java.util.Objects;
import java.util.UUID;

/**
 * O que o razão respondeu a um passo da Saga. São três casos, e a diferença entre os dois últimos é
 * o coração da Saga:
 *
 * <ul>
 *   <li>{@link Feito} — o dinheiro se moveu (ou já tinha se movido: resposta repetida);</li>
 *   <li>{@link Recusado} — o razão respondeu "não" (saldo insuficiente, conta encerrada). Certeza
 *       de que nada se moveu: dá para seguir para o próximo estado, ou compensar;</li>
 *   <li>{@link SemResposta} — tempo esgotado, razão fora do ar. <b>Não se sabe</b> se o dinheiro se
 *       moveu. Não é recusa: compensar agora poderia devolver um dinheiro que chegou. A única
 *       resposta segura é repetir o mesmo passo, com a mesma chave, até ter certeza.</li>
 * </ul>
 */
public sealed interface ResultadoDoPasso {

    record Feito(UUID lancamentoId) implements ResultadoDoPasso {
        public Feito {
            Objects.requireNonNull(lancamentoId, "lancamentoId é obrigatório");
        }
    }

    record Recusado(String codigo, String motivo) implements ResultadoDoPasso {}

    record SemResposta(String erro) implements ResultadoDoPasso {}
}
