package io.github.alanjoffre.ledgerly.pix.adaptadores.saida.redis;

import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.TravaDePedido;
import io.quarkus.redis.datasource.RedisDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import org.jboss.logging.Logger;

/**
 * Adaptador de saída: a trava rápida no Redis (ADR-0018).
 *
 * <p>{@code SET chave 1 NX PX <ms>}: grava só se a chave não existir ({@code NX}) e com validade
 * ({@code PX}), num comando só — atômico. Dois pedidos ao mesmo tempo: só um consegue.
 *
 * <p><b>Falha aberta</b> (<i>fail-open</i>): se o Redis não responder, a trava deixa passar. Ela é
 * só uma camada de velocidade; quem garante a unicidade é o banco. Um Redis fora do ar não pode
 * derrubar o Pix.
 */
@ApplicationScoped
class RedisTravaDePedido implements TravaDePedido {

    private static final Logger LOG = Logger.getLogger(RedisTravaDePedido.class);

    private final RedisDataSource redis;

    RedisTravaDePedido(RedisDataSource redis) {
        this.redis = redis;
    }

    @Override
    public boolean travar(String chave, Duration validade) {
        try {
            return redis.execute("SET", chave, "1", "NX", "PX", Long.toString(validade.toMillis())) != null;
        } catch (RuntimeException e) {
            LOG.warnf("Redis indisponível; seguindo sem a trava rápida (o banco garante a unicidade): %s", e.getMessage());
            return true;
        }
    }

    @Override
    public void liberar(String chave) {
        try {
            redis.key().del(chave);
        } catch (RuntimeException e) {
            // A trava vence sozinha pela validade.
            LOG.warnf("Não foi possível liberar a trava %s no Redis: %s", chave, e.getMessage());
        }
    }
}
