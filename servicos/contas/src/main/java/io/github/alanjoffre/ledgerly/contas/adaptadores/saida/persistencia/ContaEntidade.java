package io.github.alanjoffre.ledgerly.contas.adaptadores.saida.persistencia;

import io.github.alanjoffre.ledgerly.contas.dominio.Conta;
import io.github.alanjoffre.ledgerly.contas.dominio.NumeroConta;
import io.github.alanjoffre.ledgerly.contas.dominio.SituacaoConta;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** Entidade JPA da conta. O {@code @Version} liga a trava otimista do Hibernate. */
@Entity
@Table(name = "conta")
class ContaEntidade {

    @Id
    private UUID id;

    @Column(name = "cliente_id", nullable = false)
    private UUID clienteId;

    @Column(nullable = false, length = 4)
    private String agencia;

    @Column(nullable = false)
    private Integer numero;

    @Column(nullable = false)
    private Integer digito;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SituacaoConta situacao;

    @Column(name = "aberta_em", nullable = false)
    private Instant abertaEm;

    @Column(name = "encerrada_em")
    private Instant encerradaEm;

    /**
     * Toda gravação vira {@code UPDATE conta SET ..., versao = versao + 1 WHERE id = ? AND versao = ?}.
     * Se nenhuma linha for atualizada, outra transação gravou antes: o Hibernate lança erro.
     * Nula numa conta nova: o Hibernate entende que é inserção e começa em 0.
     */
    @Version
    private Long versao;

    protected ContaEntidade() {}

    static ContaEntidade nova(Conta conta) {
        ContaEntidade e = new ContaEntidade();
        e.id = conta.id();
        e.clienteId = conta.clienteId();
        e.agencia = conta.numero().agencia();
        e.numero = conta.numero().numero();
        e.digito = conta.numero().digito();
        e.copiarEstado(conta);
        return e;
    }

    void copiarEstado(Conta conta) {
        situacao = conta.situacao();
        encerradaEm = conta.encerradaEm();
    }

    Conta paraDominio() {
        return Conta.reconstituir(
                id, clienteId, new NumeroConta(agencia, numero, digito), situacao, abertaEm, encerradaEm, versao);
    }

    void definirAbertura(Instant abertaEm) {
        this.abertaEm = abertaEm;
    }

    Long versao() {
        return versao;
    }
}
