package io.github.alanjoffre.ledgerly.contas.adaptadores.saida.eventos;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Liga o agendador do Spring ({@code @Scheduled}), que roda o {@link PublicadorDoOutbox}. O produtor
 * Kafka (idempotente, {@code acks=all}, tempos-limite) é configurado no {@code application.yaml}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class ConfiguracaoEventos {}
