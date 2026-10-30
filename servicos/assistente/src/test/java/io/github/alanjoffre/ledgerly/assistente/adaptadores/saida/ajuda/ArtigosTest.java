package io.github.alanjoffre.ledgerly.assistente.adaptadores.saida.ajuda;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.alanjoffre.ledgerly.assistente.dominio.Trecho;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Artigos de ajuda")
class ArtigosTest {

    @Test
    @DisplayName("um trecho por seção ##, com o título do artigo")
    void cortar() {
        assertThat(Artigos.cortar("tarifas", """
                # Tarifas e horários

                ## Tarifas
                Não cobra.

                ## Horários
                24 horas.
                """)).containsExactly(
                new Trecho("tarifas", "Tarifas e horários", "Tarifas:\nNão cobra."),
                new Trecho("tarifas", "Tarifas e horários", "Horários:\n24 horas."));
    }

    @Test
    @DisplayName("os artigos do serviço: todos com título e sem valor de cliente; a versão muda com o texto e o modelo")
    void artigosDoServico() {
        List<Trecho> trechos = Artigos.ler("classpath:ajuda/*.md");
        assertThat(trechos).hasSizeGreaterThanOrEqualTo(15);
        assertThat(trechos).allSatisfy(t -> {
            assertThat(t.titulo()).isNotBlank().doesNotStartWith("#");
            assertThat(t.texto()).isNotBlank();
        });
        assertThat(trechos).extracting(Trecho::artigo).contains("pix-retido", "golpes-comuns");

        String versao = Artigos.versao(trechos, "bge-m3");
        assertThat(Artigos.versao(trechos, "bge-m3")).isEqualTo(versao);
        assertThat(Artigos.versao(trechos, "outro")).isNotEqualTo(versao);
        assertThat(Artigos.versao(trechos.subList(1, trechos.size()), "bge-m3")).isNotEqualTo(versao);
    }
}
