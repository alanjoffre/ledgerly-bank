package io.github.alanjoffre.ledgerly.assistente.adaptadores.saida.bancario;

import io.github.alanjoffre.ledgerly.assistente.aplicacao.BancarioIndisponivelException;
import io.github.alanjoffre.ledgerly.assistente.aplicacao.Cliente;
import io.github.alanjoffre.ledgerly.assistente.aplicacao.porta.Bancario;
import io.github.alanjoffre.ledgerly.assistente.dominio.Conta;
import io.github.alanjoffre.ledgerly.assistente.dominio.Lancamento;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * O contas ({@code GET /v1/clientes/{id}/contas}) e o extrato ({@code /saldo}, {@code /extrato}),
 * chamados com o token da pessoa — como a tela do internet banking chamaria. Quem confere que a
 * conta é dela é o serviço de lá (ADR-0026); aqui, 404 é "sem movimento" e qualquer outro erro é
 * "fora do ar".
 */
class HttpBancario implements Bancario {

    private final RestClient contas;
    private final RestClient extrato;

    HttpBancario(RestClient contas, RestClient extrato) {
        this.contas = contas;
        this.extrato = extrato;
    }

    record ContaJson(UUID id, String agencia, String numero, String situacao) {}

    record SaldoJson(String saldo) {}

    record ItemJson(String tipo, String valor, String saldoApos, String descricao, Instant registradoEm) {}

    record ExtratoJson(List<ItemJson> itens) {}

    @Override
    public List<Conta> contas(Cliente cliente) {
        ContaJson[] lidas = chamar(() -> contas.get().uri("/v1/clientes/{id}/contas", cliente.clienteId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + cliente.token())
                .retrieve().body(ContaJson[].class), null);
        return lidas == null ? List.of() : List.of(lidas).stream()
                .filter(c -> !"ENCERRADA".equals(c.situacao()))
                .map(c -> new Conta(c.id(), c.agencia(), c.numero()))
                .toList();
    }

    @Override
    public BigDecimal saldo(Cliente cliente, UUID contaId) {
        SaldoJson saldo = chamar(() -> extrato.get().uri("/v1/contas/{id}/saldo", contaId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + cliente.token())
                .retrieve().body(SaldoJson.class), null);
        return saldo == null || saldo.saldo() == null ? BigDecimal.ZERO.setScale(2) : new BigDecimal(saldo.saldo());
    }

    @Override
    public List<Lancamento> lancamentos(Cliente cliente, UUID contaId, int limite) {
        ExtratoJson pagina = chamar(() -> extrato.get().uri("/v1/contas/{id}/extrato?limite={limite}", contaId, limite)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + cliente.token())
                .retrieve().body(ExtratoJson.class), null);
        return pagina == null || pagina.itens() == null ? List.of() : pagina.itens().stream()
                .map(i -> new Lancamento(i.registradoEm(), "CREDITO".equals(i.tipo()),
                        new BigDecimal(i.valor()), new BigDecimal(i.saldoApos()), i.descricao()))
                .toList();
    }

    /** 404 vira {@code seNaoExiste}; qualquer outra falha, {@link BancarioIndisponivelException}. */
    private static <T> T chamar(Supplier<T> chamada, T seNaoExiste) {
        try {
            return chamada.get();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                return seNaoExiste;
            }
            throw new BancarioIndisponivelException("respondeu " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            throw new BancarioIndisponivelException(e.getMessage(), e);
        }
    }
}
