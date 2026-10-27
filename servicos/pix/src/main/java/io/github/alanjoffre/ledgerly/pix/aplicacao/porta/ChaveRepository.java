package io.github.alanjoffre.ledgerly.pix.aplicacao.porta;

import io.github.alanjoffre.ledgerly.pix.dominio.ChavePix;
import io.github.alanjoffre.ledgerly.pix.dominio.ChaveRegistrada;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Porta de saída: o diretório de chaves (o "DICT" do Ledgerly). */
public interface ChaveRepository {

    /** @throws io.github.alanjoffre.ledgerly.pix.dominio.ChaveJaCadastradaException se a chave já existe */
    void inserir(ChaveRegistrada chave);

    Optional<ChaveRegistrada> buscar(ChavePix chave);

    List<ChaveRegistrada> listarDaConta(UUID contaId);

    /** Quantas chaves a conta tem, travando as chaves dela (dois registros simultâneos entram em fila). */
    int contarDaContaTravando(UUID contaId);

    boolean excluir(ChavePix chave);
}
