package io.github.alanjoffre.ledgerly.pix.adaptadores.entrada.web;

import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import io.github.alanjoffre.ledgerly.pix.dominio.BrCode;
import io.github.alanjoffre.ledgerly.pix.dominio.ChaveRegistrada;
import io.github.alanjoffre.ledgerly.pix.dominio.SituacaoTransferencia;
import io.github.alanjoffre.ledgerly.pix.dominio.TipoChave;
import io.github.alanjoffre.ledgerly.pix.dominio.TransferenciaPix;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * Os formatos JSON da API do Pix. Dinheiro é texto ({@code "10.50"}), como nos outros serviços
 * (ADR-0012).
 */
final class Dtos {

    private Dtos() {}

    static final String PADRAO_VALOR = "^\\d{1,17}(\\.\\d{1,2})?$";
    static final String MENSAGEM_VALOR = "deve ser um valor como \"10.50\" (ponto decimal, até 2 casas)";

    // ---- Entrada ----

    /** Para {@code ALEATORIA}, não envie {@code valor}: o banco gera. */
    record RegistrarChaveRequisicao(@NotNull UUID contaId, @NotNull TipoChave tipo, String valor) {}

    record LerQrCodeRequisicao(@NotBlank String copiaECola) {}

    /** A decisão do analista: para qual conta (do mesmo titular) o dinheiro volta, e por quê. */
    record DevolucaoRequisicao(@NotNull UUID contaDestinoId, @NotBlank @Size(min = 10, max = 500) String justificativa) {}

    /** Informe {@code chave} OU {@code copiaECola}. */
    record TransferenciaRequisicao(
            @NotNull UUID pagadorContaId,
            String chave,
            String copiaECola,
            @Pattern(regexp = PADRAO_VALOR, message = MENSAGEM_VALOR) String valor,
            @Size(max = TransferenciaPix.TAMANHO_MAXIMO_DESCRICAO) String descricao) {}

    // ---- Saída ----

    /** A chave para o próprio dono: inteira. */
    record ChaveResposta(String chave, TipoChave tipo, UUID contaId, String titular, Instant registradaEm) {

        static ChaveResposta de(ChaveRegistrada c) {
            return new ChaveResposta(c.chave().valor(), c.chave().tipo(), c.contaId(), c.titularNome(), c.registradaEm());
        }
    }

    /** A chave consultada por quem vai pagar: mascarada e sem o id da conta. */
    record ConsultaChaveResposta(String chave, TipoChave tipo, String titular, String instituicao) {

        static ConsultaChaveResposta de(ChaveRegistrada c) {
            return new ConsultaChaveResposta(c.chave().mascarada(), c.chave().tipo(), c.titularNome(), "Ledgerly Bank");
        }
    }

    record QrCodeResposta(String copiaECola, String chave, TipoChave tipoChave, String valor, String nome, String cidade, String descricao) {

        static QrCodeResposta de(BrCode qr) {
            return new QrCodeResposta(
                    qr.copiaECola(),
                    qr.chave().valor(),
                    qr.chave().tipo(),
                    qr.valorFixo().map(Dtos::texto).orElse(null),
                    qr.nomeRecebedor(),
                    qr.cidade(),
                    qr.descricao());
        }
    }

    record Pagador(UUID contaId, String nome) {}

    record Recebedor(String nome, String chave, TipoChave tipoChave) {}

    record Lancamentos(UUID envio, UUID recebimento, UUID estorno) {}

    record TransferenciaResposta(
            UUID id,
            SituacaoTransferencia situacao,
            String valor,
            String moeda,
            String descricao,
            Pagador pagador,
            Recebedor recebedor,
            String motivo,
            Lancamentos lancamentos,
            UUID contaDevolucaoId,
            int tentativas,
            Instant proximaTentativaEm,
            Instant criadaEm,
            Instant finalizadaEm) {

        static TransferenciaResposta de(TransferenciaPix t) {
            boolean emAndamento = !t.situacao().ehFinal();
            return new TransferenciaResposta(
                    t.id(),
                    t.situacao(),
                    texto(t.valor()),
                    t.valor().moeda().getCurrencyCode(),
                    t.descricao(),
                    new Pagador(t.pagador().contaId(), t.pagador().nome()),
                    new Recebedor(t.recebedor().nome(), t.chaveDoRecebedor().mascarada(), t.chaveDoRecebedor().tipo()),
                    t.motivo(),
                    new Lancamentos(t.lancamentoEnvioId(), t.lancamentoRecebimentoId(), t.lancamentoEstornoId()),
                    t.contaDevolucaoId(),
                    t.tentativas(),
                    emAndamento ? t.proximaTentativaEm() : null,
                    t.criadaEm(),
                    t.finalizadaEm());
        }
    }

    static String texto(Dinheiro dinheiro) {
        return dinheiro.valor().toPlainString();
    }

    static Dinheiro dinheiro(String valor) {
        return valor == null || valor.isBlank() ? null : Dinheiro.reais(valor);
    }
}
