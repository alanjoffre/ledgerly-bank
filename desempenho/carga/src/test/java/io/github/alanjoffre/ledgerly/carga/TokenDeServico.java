package io.github.alanjoffre.ledgerly.carga;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;

/**
 * Token de serviço para a carga: client credentials no Keycloak, com o cliente LOCAL
 * "ledgerly-ferramentas" (o mesmo de scripts/lib/token.sh). Vale 5 minutos — mais que uma carga.
 */
final class TokenDeServico {

    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"access_token\":\"([^\"]+)\"");

    private TokenDeServico() {}

    static String pedir(String emissor) {
        var corpo = "grant_type=client_credentials&client_id=ledgerly-ferramentas&client_secret="
                + System.getProperty("segredo", "ferramentas-segredo-local");
        try {
            var resposta = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(emissor + "/protocol/openid-connect/token"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(corpo))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            var achado = ACCESS_TOKEN.matcher(resposta.body());
            if (resposta.statusCode() != 200 || !achado.find()) {
                throw new IllegalStateException("o Keycloak respondeu " + resposta.statusCode());
            }
            return achado.group(1);
        } catch (Exception e) {
            throw new IllegalStateException("Sem token de serviço em " + emissor + " (o Keycloak está no ar?)", e);
        }
    }
}
