package io.github.alanjoffre.ledgerly.pix.integracao;

import java.util.HashMap;
import java.util.Map;

/**
 * Os testes de contrato do Pix: o Keycloak continua falso (o token vai de verdade), e os três clientes
 * HTTP apontam para o servidor do Pact, que sobe em porta fixa porque o Quarkus lê o endereço antes.
 */
public class ContratoRecurso extends ServicosFalsos {

    static final String PORTA = "18999";

    @Override
    public Map<String, String> start() {
        Map<String, String> config = new HashMap<>(super.start());
        String url = "http://127.0.0.1:" + PORTA;
        config.put("quarkus.rest-client.razao.url", url);
        config.put("quarkus.rest-client.contas.url", url);
        config.put("quarkus.rest-client.antifraude.url", url);
        return config;
    }
}
