package io.github.alanjoffre.ledgerly.extrato.aplicacao;

import io.github.alanjoffre.ledgerly.extrato.aplicacao.porta.MovimentacaoRepository;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.porta.SaldoRepository;
import io.github.alanjoffre.ledgerly.extrato.dominio.Movimentacao;
import io.github.alanjoffre.ledgerly.extrato.dominio.ParteDoPix;
import io.github.alanjoffre.ledgerly.extrato.dominio.ParteDoRazao;
import io.github.alanjoffre.ledgerly.extrato.dominio.Saldo;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * As portas em memória, com a mesma semântica dos adaptadores do MongoDB (upsert por pedaço,
 * saldo só substitui se for mais novo). Deixa testar a aplicação em milissegundos, sem Docker.
 */
final class RepositoriosEmMemoria implements MovimentacaoRepository, SaldoRepository {

    /** Um documento: os dois pedaços, cada um podendo faltar. */
    record Documento(ParteDoRazao razao, ParteDoPix pix) {}

    record Chave(UUID lancamentoId, UUID contaId) {}

    final Map<Chave, Documento> documentos = new HashMap<>();
    final Map<UUID, Saldo> saldos = new HashMap<>();

    @Override
    public void gravarParteDoRazao(UUID lancamentoId, UUID contaId, ParteDoRazao parte) {
        documentos.merge(new Chave(lancamentoId, contaId), new Documento(parte, null),
                (atual, novo) -> new Documento(parte, atual.pix()));
    }

    @Override
    public void gravarParteDoPix(UUID lancamentoId, UUID contaId, ParteDoPix parte) {
        documentos.merge(new Chave(lancamentoId, contaId), new Documento(null, parte),
                (atual, novo) -> new Documento(atual.razao(), parte));
    }

    @Override
    public List<Movimentacao> pagina(UUID contaId, Long antesDe, int limite) {
        return documentos.entrySet().stream()
                .filter(e -> e.getKey().contaId().equals(contaId) && e.getValue().razao() != null)
                .filter(e -> antesDe == null || e.getValue().razao().sequencia() < antesDe)
                .map(e -> new Movimentacao(e.getKey().lancamentoId(), contaId, e.getValue().razao(), e.getValue().pix()))
                .sorted(Comparator.comparingLong((Movimentacao m) -> m.razao().sequencia()).reversed())
                .limit(limite)
                .toList();
    }

    @Override
    public boolean atualizarSeMaisNovo(Saldo saldo) {
        if (saldo.ehMaisNovoQue(saldos.get(saldo.contaId()))) {
            saldos.put(saldo.contaId(), saldo);
            return true;
        }
        return false;
    }

    @Override
    public Optional<Saldo> buscar(UUID contaId) {
        return Optional.ofNullable(saldos.get(contaId));
    }
}
