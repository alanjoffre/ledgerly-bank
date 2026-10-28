package io.github.alanjoffre.ledgerly.conciliacao;

import java.util.List;
import java.util.stream.Stream;

/** Uma fonte de teste: uma lista, ordenada como o banco ordenaria, filtrada pela faixa. */
record FonteEmMemoria(List<Registro> registros) implements Fonte {

    FonteEmMemoria(Registro... registros) {
        this(List.of(registros));
    }

    @Override
    public Stream<Registro> ler(Faixa faixa) {
        return registros.stream()
                .filter(r -> faixa.contem(r.id()))
                .sorted((a, b) -> OrdemDosIds.comparar(a.id(), b.id()));
    }
}
