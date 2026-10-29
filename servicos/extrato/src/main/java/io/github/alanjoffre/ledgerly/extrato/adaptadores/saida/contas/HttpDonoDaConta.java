package io.github.alanjoffre.ledgerly.extrato.adaptadores.saida.contas;

import io.github.alanjoffre.ledgerly.extrato.aplicacao.ContasIndisponivelException;
import io.github.alanjoffre.ledgerly.extrato.aplicacao.porta.DonoDaConta;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * O dono da conta, perguntado ao contas ({@code GET /v1/contas/{id}/titular}, com token de serviço).
 *
 * <p><b>Cache sem prazo</b>: o dono de uma conta nunca muda (não existe "transferir a conta para
 * outro cliente"), então a resposta guardada não envelhece — o primeiro extrato da conta custa uma
 * chamada ao contas; os seguintes, nenhuma. O limite de tamanho (as menos usadas saem primeiro, o
 * LRU) só segura a memória. "Conta não existe" não é guardado: ela pode ser aberta no segundo
 * seguinte.
 */
class HttpDonoDaConta implements DonoDaConta {

    private final RestClient http;
    private final Map<UUID, UUID> donos;

    HttpDonoDaConta(RestClient contasRestClient, int limiteDoCache) {
        this.http = contasRestClient;
        this.donos = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, UUID> maisAntiga) {
                return size() > limiteDoCache;
            }
        });
    }

    record TitularJson(UUID contaId, UUID clienteId) {}

    @Override
    public Optional<UUID> clienteDe(UUID contaId) {
        UUID guardado = donos.get(contaId);
        if (guardado != null) {
            return Optional.of(guardado);
        }
        try {
            TitularJson titular = http.get().uri("/v1/contas/{id}/titular", contaId).retrieve().body(TitularJson.class);
            if (titular == null || titular.clienteId() == null) {
                throw new ContasIndisponivelException("resposta vazia do contas", null);
            }
            donos.put(contaId, titular.clienteId());
            return Optional.of(titular.clienteId());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                return Optional.empty();
            }
            throw new ContasIndisponivelException("contas respondeu " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            throw new ContasIndisponivelException(e.getMessage(), e);
        }
    }

    int tamanhoDoCache() {
        return donos.size();
    }
}
