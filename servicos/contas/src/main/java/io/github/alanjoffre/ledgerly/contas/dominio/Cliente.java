package io.github.alanjoffre.ledgerly.contas.dominio;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Pessoa cliente do banco.
 *
 * @param id           identificador
 * @param nome         nome completo, de 2 a 120 caracteres
 * @param cpf          CPF validado
 * @param cadastradoEm quando foi cadastrada
 */
public record Cliente(UUID id, String nome, Cpf cpf, Instant cadastradoEm) {

    public static final int TAMANHO_MAXIMO_NOME = 120;

    public Cliente {
        Objects.requireNonNull(id, "id é obrigatório");
        Objects.requireNonNull(cpf, "CPF é obrigatório");
        // Precisão do PostgreSQL (microssegundos): o que se devolve é o que se lê depois.
        cadastradoEm = Objects.requireNonNull(cadastradoEm, "cadastradoEm é obrigatório").truncatedTo(ChronoUnit.MICROS);
        nome = Objects.requireNonNull(nome, "nome é obrigatório").strip().replaceAll("\\s+", " ");
        if (nome.length() < 2 || nome.length() > TAMANHO_MAXIMO_NOME) {
            throw new DadoInvalidoException("O nome deve ter de 2 a %d caracteres".formatted(TAMANHO_MAXIMO_NOME));
        }
    }

    public static Cliente cadastrar(String nome, Cpf cpf, Instant agora) {
        return new Cliente(UUID.randomUUID(), nome, cpf, agora);
    }
}
