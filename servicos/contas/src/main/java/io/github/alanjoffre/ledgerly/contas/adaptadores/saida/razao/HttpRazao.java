package io.github.alanjoffre.ledgerly.contas.adaptadores.saida.razao;

import io.github.alanjoffre.ledgerly.contas.aplicacao.RazaoIndisponivelException;
import io.github.alanjoffre.ledgerly.contas.aplicacao.porta.Razao;
import io.github.alanjoffre.ledgerly.contas.dominio.EncerramentoComSaldoException;
import io.github.alanjoffre.ledgerly.contas.dominio.SituacaoConta;
import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Currency;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Adaptador de saída: o serviço razão, por HTTP.
 *
 * <p>Traduz as respostas do razão para a linguagem deste serviço:
 * <ul>
 *   <li>422 {@code encerramento-com-saldo} → {@link EncerramentoComSaldoException} (regra de negócio);</li>
 *   <li>5xx, tempo esgotado, conexão recusada → {@link RazaoIndisponivelException} (a API responde 503);</li>
 *   <li>outros 4xx → {@link IllegalStateException}: um pedido malformado nosso é erro de programação.</li>
 * </ul>
 *
 * <p>Os tempos-limite ficam no {@link RestClient} (ver {@link ConfiguracaoRazao}): sem eles, um razão
 * lento prenderia as threads deste serviço indefinidamente.
 */
@Component
class HttpRazao implements Razao {

    private static final String ENCERRAMENTO_COM_SALDO = "urn:ledgerly:erro:encerramento-com-saldo";

    private final RestClient http;

    HttpRazao(RestClient razaoRestClient) {
        this.http = razaoRestClient;
    }

    record AbrirContaJson(String moeda) {}

    record SituacaoJson(SituacaoConta situacao) {}

    record ContaRazaoJson(UUID id, String moeda, String saldo, SituacaoConta situacao) {}

    @Override
    public void abrirConta(UUID contaId) {
        chamar("abrir a conta " + contaId, () -> http.put()
                .uri("/v1/contas/{id}", contaId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new AbrirContaJson(Dinheiro.REAL.getCurrencyCode()))
                .retrieve()
                .toBodilessEntity());
    }

    @Override
    public void alterarSituacao(UUID contaId, SituacaoConta situacao) {
        chamar("alterar a situação da conta " + contaId, () -> http.put()
                .uri("/v1/contas/{id}/situacao", contaId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new SituacaoJson(situacao))
                .retrieve()
                .toBodilessEntity());
    }

    @Override
    public Dinheiro consultarSaldo(UUID contaId) {
        ContaRazaoJson conta = chamar("consultar o saldo da conta " + contaId, () -> http.get()
                .uri("/v1/contas/{id}", contaId)
                .retrieve()
                .body(ContaRazaoJson.class));
        if (conta == null) {
            throw new IllegalStateException("O razão respondeu sem corpo ao consultar a conta " + contaId);
        }
        return Dinheiro.de(new BigDecimal(conta.saldo()), Currency.getInstance(conta.moeda()));
    }

    private static <T> T chamar(String acao, Supplier<T> chamada) {
        try {
            return chamada.get();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is5xxServerError()) {
                throw new RazaoIndisponivelException("O razão falhou ao " + acao, e);
            }
            ProblemDetail problema = e.getResponseBodyAs(ProblemDetail.class);
            URI tipo = problema == null ? null : problema.getType();
            if (e.getStatusCode().isSameCodeAs(HttpStatus.UNPROCESSABLE_CONTENT)
                    && tipo != null
                    && ENCERRAMENTO_COM_SALDO.equals(tipo.toString())) {
                throw new EncerramentoComSaldoException(problema.getDetail());
            }
            throw new IllegalStateException("O razão recusou o pedido para " + acao + ": " + e.getStatusCode(), e);
        } catch (ResourceAccessException e) {
            throw new RazaoIndisponivelException("O razão não respondeu ao " + acao, e);
        }
    }
}
