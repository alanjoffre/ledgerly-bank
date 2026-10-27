package io.github.alanjoffre.ledgerly.contas.adaptadores.saida.razao;

import io.github.alanjoffre.ledgerly.contas.aplicacao.RazaoIndisponivelException;
import io.github.alanjoffre.ledgerly.contas.aplicacao.porta.Razao;
import io.github.alanjoffre.ledgerly.contas.dominio.EncerramentoComSaldoException;
import io.github.alanjoffre.ledgerly.contas.dominio.SituacaoConta;
import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Optional;
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
 *   <li>404 {@code conta-nao-encontrada} → vazio: o razão ainda não recebeu o evento {@code ContaAberta};</li>
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
    private static final String CONTA_NAO_ENCONTRADA = "urn:ledgerly:erro:conta-nao-encontrada";

    private final RestClient http;

    HttpRazao(RestClient razaoRestClient) {
        this.http = razaoRestClient;
    }

    record SituacaoJson(SituacaoConta situacao) {}

    record ContaRazaoJson(UUID id, String moeda, String saldo, SituacaoConta situacao) {}

    /**
     * {@code PUT /v1/contas/{id}/situacao} com {@code ENCERRADA}.
     *
     * <p>{@code 404 conta-nao-encontrada} (o razão ainda não conhece a conta) conta como sucesso: o
     * evento {@code ContaAberta} ainda não chegou lá, então nenhum movimento foi possível e o saldo é
     * zero por construção. O evento {@code ContaEncerrada}, gravado nesta mesma transação, sai DEPOIS
     * do {@code ContaAberta} (mesma chave, mesma partição, em ordem), e o razão abre e encerra a
     * conta nessa ordem.
     */
    @Override
    public void encerrarConta(UUID contaId) {
        chamar("encerrar a conta " + contaId, () -> http.put()
                .uri("/v1/contas/{id}/situacao", contaId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new SituacaoJson(SituacaoConta.ENCERRADA))
                .retrieve()
                .toBodilessEntity());
    }

    @Override
    public Optional<Dinheiro> consultarSaldo(UUID contaId) {
        return chamar("consultar o saldo da conta " + contaId, () -> {
                    ContaRazaoJson conta = http.get().uri("/v1/contas/{id}", contaId).retrieve().body(ContaRazaoJson.class);
                    if (conta == null) {
                        throw new IllegalStateException("O razão respondeu sem corpo ao consultar a conta " + contaId);
                    }
                    return conta;
                })
                .map(conta -> Dinheiro.de(new BigDecimal(conta.saldo()), Currency.getInstance(conta.moeda())));
    }

    /**
     * Faz a chamada e traduz a resposta. Vazio = {@code 404 conta-nao-encontrada}: o razão ainda não
     * conhece a conta. Qualquer outro 404 (um endereço errado na configuração, por exemplo) é erro —
     * não pode ser lido como "saldo zero".
     */
    private static <T> Optional<T> chamar(String acao, Supplier<T> chamada) {
        try {
            return Optional.of(chamada.get());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is5xxServerError()) {
                throw new RazaoIndisponivelException("O razão falhou ao " + acao, e);
            }
            ProblemDetail problema = e.getResponseBodyAs(ProblemDetail.class);
            String tipo = problema == null || problema.getType() == null ? "" : problema.getType().toString();
            if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND) && CONTA_NAO_ENCONTRADA.equals(tipo)) {
                return Optional.empty();
            }
            if (e.getStatusCode().isSameCodeAs(HttpStatus.UNPROCESSABLE_CONTENT) && ENCERRAMENTO_COM_SALDO.equals(tipo)) {
                throw new EncerramentoComSaldoException(problema.getDetail());
            }
            throw new IllegalStateException("O razão recusou o pedido para " + acao + ": " + e.getStatusCode(), e);
        } catch (ResourceAccessException e) {
            throw new RazaoIndisponivelException("O razão não respondeu ao " + acao, e);
        }
    }
}
