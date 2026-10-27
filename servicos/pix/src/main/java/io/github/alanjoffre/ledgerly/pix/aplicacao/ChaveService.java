package io.github.alanjoffre.ledgerly.pix.aplicacao;

import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.ChaveRepository;
import io.github.alanjoffre.ledgerly.pix.aplicacao.porta.Contas;
import io.github.alanjoffre.ledgerly.pix.dominio.ChaveInvalidaException;
import io.github.alanjoffre.ledgerly.pix.dominio.ChaveNaoEncontradaException;
import io.github.alanjoffre.ledgerly.pix.dominio.ChavePix;
import io.github.alanjoffre.ledgerly.pix.dominio.ChaveRegistrada;
import io.github.alanjoffre.ledgerly.pix.dominio.ContaInativaException;
import io.github.alanjoffre.ledgerly.pix.dominio.ContaNaoEncontradaException;
import io.github.alanjoffre.ledgerly.pix.dominio.LimiteDeChavesException;
import io.github.alanjoffre.ledgerly.pix.dominio.TipoChave;
import io.github.alanjoffre.ledgerly.pix.dominio.TitularidadeNaoConfereException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** Casos de uso das chaves Pix: registrar, consultar (o "confira o destinatário"), listar e excluir. */
@ApplicationScoped
public class ChaveService {

    private final ChaveRepository chaves;
    private final Contas contas;
    private final Clock relogio;

    public ChaveService(ChaveRepository chaves, Contas contas, Clock relogio) {
        this.chaves = chaves;
        this.contas = contas;
        this.relogio = relogio;
    }

    /**
     * Registra uma chave para a conta.
     *
     * <ul>
     *   <li>a conta precisa existir e estar ativa;</li>
     *   <li>chave CPF só se o CPF for o do titular (o serviço de contas responde sim ou não);</li>
     *   <li>até {@value ChaveRegistrada#MAXIMO_POR_CONTA} chaves por conta;</li>
     *   <li>a chave aleatória é gerada aqui — quem pede não escolhe o valor.</li>
     * </ul>
     *
     * <p>A conferência com o serviço de contas acontece dentro da transação: registrar chave é raro,
     * e assim a contagem do limite e a gravação ficam juntas.
     */
    @Transactional
    public ChaveRegistrada registrar(UUID contaId, TipoChave tipo, String valor) {
        ChavePix chave;
        if (tipo == TipoChave.ALEATORIA) {
            if (valor != null && !valor.isBlank()) {
                throw new ChaveInvalidaException("A chave aleatória é gerada pelo banco: não envie o valor");
            }
            chave = ChavePix.aleatoria();
        } else {
            chave = new ChavePix(tipo, valor);
        }

        Contas.ContaResumo conta = contas.buscar(contaId)
                .orElseThrow(() -> new ContaNaoEncontradaException("Conta %s não encontrada".formatted(contaId)));
        if (!conta.ativa()) {
            throw new ContaInativaException("A conta %s não está ativa".formatted(contaId));
        }
        if (tipo == TipoChave.CPF && !contas.titularidadeConfere(contaId, chave.valor())) {
            throw new TitularidadeNaoConfereException("O CPF não é o do titular da conta");
        }
        if (chaves.contarDaContaTravando(contaId) >= ChaveRegistrada.MAXIMO_POR_CONTA) {
            throw new LimiteDeChavesException(
                    "A conta já tem %d chaves, o máximo permitido".formatted(ChaveRegistrada.MAXIMO_POR_CONTA));
        }
        ChaveRegistrada registrada = new ChaveRegistrada(chave, contaId, conta.titularNome(), relogio.instant());
        chaves.inserir(registrada);
        return registrada;
    }

    /** Quem é o dono da chave — o que o app mostra antes de confirmar o Pix. */
    public ChaveRegistrada consultar(String chave) {
        ChavePix procurada = ChavePix.identificar(chave);
        return chaves.buscar(procurada)
                .orElseThrow(() -> new ChaveNaoEncontradaException("Nenhuma conta tem a chave " + procurada.mascarada()));
    }

    public List<ChaveRegistrada> listarDaConta(UUID contaId) {
        return chaves.listarDaConta(contaId);
    }

    @Transactional
    public void excluir(String chave) {
        ChavePix procurada = ChavePix.identificar(chave);
        if (!chaves.excluir(procurada)) {
            throw new ChaveNaoEncontradaException("Nenhuma conta tem a chave " + procurada.mascarada());
        }
    }
}
