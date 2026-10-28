package io.github.alanjoffre.ledgerly.conciliacao;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Programa de linha de comando da conciliação")
class ConciliacaoEmLoteTest {

    @Test
    @DisplayName("mede os dois jeitos, mostra os números e confere que deram o mesmo resultado")
    void executar() throws Exception {
        var bytes = new ByteArrayOutputStream();
        Resultado r = ConciliacaoEmLote.executar(300_000, 4, 1, new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String saida = bytes.toString(StandardCharsets.UTF_8);

        assertThat(saida).contains("Em sequência", "Em paralelo", "Ganho:", "mesmo resultado: sim");
        assertThat(r.conferidos()).isGreaterThan(300_000);
        assertThat(r.amostra()).extracting(Divergencia::execucao).allMatch(e -> e.startsWith("conciliacao-"));
    }

    @Test
    @DisplayName("main com argumentos")
    void main() throws Exception {
        PrintStream original = System.out;
        var bytes = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        try {
            ConciliacaoEmLote.main(new String[] {"10000", "2", "1"});
        } finally {
            System.setOut(original);
        }
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("10,000 registros por lado, 2 faixas");
    }
}
