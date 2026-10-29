package io.github.alanjoffre.ledgerly.contas.aplicacao;

import java.util.UUID;

/**
 * Quem pede pode ver os dados deste cliente? É a autorização por objeto (ADR-0026): o papel do
 * token diz se a pessoa pode ler contas; esta pergunta diz se pode ler ESTAS.
 *
 * <p>Quem responde é o adaptador web, a partir do token; o caso de uso só pergunta. Assim a
 * aplicação continua sem depender do Spring Security (a regra do ArchUnit), e a pergunta é feita
 * antes de ir ao razão: quem não é dono não custa uma chamada de rede, nem mede pelo tempo de
 * resposta se a conta existe.
 */
@FunctionalInterface
public interface PermissaoDeLeitura {

    /** Para uso interno e para os testes: lê tudo. */
    PermissaoDeLeitura TOTAL = cliente -> true;

    boolean podeVer(UUID clienteId);
}
