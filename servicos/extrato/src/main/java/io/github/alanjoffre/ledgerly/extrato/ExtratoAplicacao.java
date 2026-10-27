package io.github.alanjoffre.ledgerly.extrato;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Ponto de entrada do serviço extrato.
 *
 * <p>Pacotes (arquitetura hexagonal):
 * <ul>
 *   <li>{@code dominio} — as peças de uma linha do extrato e como ela aparece, Java puro;</li>
 *   <li>{@code aplicacao} — a projeção (eventos → documentos) e a consulta, e as portas;</li>
 *   <li>{@code adaptadores.entrada.kafka} — os consumidores dos eventos;</li>
 *   <li>{@code adaptadores.entrada.web} — a API REST de leitura;</li>
 *   <li>{@code adaptadores.saida.mongo} — o MongoDB.</li>
 * </ul>
 */
@SpringBootApplication
public class ExtratoAplicacao {

    public static void main(String[] args) {
        SpringApplication.run(ExtratoAplicacao.class, args);
    }
}
