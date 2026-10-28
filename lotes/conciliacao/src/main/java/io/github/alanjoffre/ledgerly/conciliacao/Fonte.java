package io.github.alanjoffre.ledgerly.conciliacao;

import java.util.stream.Stream;

/**
 * De onde vêm os registros de um dos lados (o Pix ou o razão).
 *
 * <p><b>O contrato</b>, de que o merge join depende: só os registros da faixa, em ordem crescente
 * de id pela {@link OrdemDosIds#DO_BANCO}, sem id repetido. O {@link Conciliador} confere a ordem
 * enquanto lê e para tudo se ela quebrar — conciliar dados fora de ordem daria um resultado errado
 * sem aviso nenhum.
 *
 * <p>O {@link Stream} é fechado por quem lê (try-with-resources): numa fonte de banco, fechar
 * devolve a conexão.
 */
@FunctionalInterface
public interface Fonte {

    Stream<Registro> ler(Faixa faixa);
}
