package io.github.alanjoffre.ledgerly.pix.integracao;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@QuarkusTest
@WithTestResource(ServicosFalsos.class)
@DisplayName("QR Code do Pix")
class QrCodeIT extends TesteDeIntegracao {

    @Test
    @DisplayName("gerar com valor, ler e pagar pelo copia e cola: CONCLUIDA com o valor do QR Code")
    void gerarLerEPagar() {
        Conta joao = novaConta("João", "100.00");
        Conta maria = novaConta("Maria Conceição", "0.00");
        String email = chaveEmail(maria);

        String copiaECola = given().queryParam("valor", "35.90").queryParam("descricao", "Bolo")
                .get("/v1/chaves/{chave}/qrcode", email).then()
                .statusCode(200)
                .body("copiaECola", startsWith("000201"))
                .body("nome", equalTo("MARIA CONCEICAO"))
                .body("valor", equalTo("35.90"))
                .extract().path("copiaECola");

        given().contentType(ContentType.JSON).body(Map.of("copiaECola", copiaECola))
                .post("/v1/qrcodes/leitura").then()
                .statusCode(200)
                .body("chave", equalTo(email))
                .body("valor", equalTo("35.90"))
                .body("descricao", equalTo("Bolo"));

        given().contentType(ContentType.JSON)
                .header("Idempotency-Key", chave())
                .body(Map.of("pagadorContaId", joao.id().toString(), "copiaECola", copiaECola))
                .post("/v1/transferencias").then()
                .statusCode(201)
                .body("situacao", equalTo("CONCLUIDA"))
                .body("valor", equalTo("35.90"))
                .body("descricao", equalTo("Bolo"));
        assertThat(ServicosFalsos.RAZAO.saldo(maria.id())).isEqualByComparingTo("35.90");
    }

    @Test
    @DisplayName("QR Code com valor diferente do pedido: 422; QR Code sem valor e pedido sem valor: 422")
    void valores() {
        Conta joao = novaConta("João", "100.00");
        Conta maria = novaConta("Maria", "0.00");
        String email = chaveEmail(maria);
        String comValor = given().queryParam("valor", "10.00").get("/v1/chaves/{c}/qrcode", email).then().extract().path("copiaECola");
        String semValor = given().get("/v1/chaves/{c}/qrcode", email).then().extract().path("copiaECola");

        given().contentType(ContentType.JSON).header("Idempotency-Key", chave())
                .body(Map.of("pagadorContaId", joao.id().toString(), "copiaECola", comValor, "valor", "11.00"))
                .post("/v1/transferencias").then().statusCode(422);
        given().contentType(ContentType.JSON).header("Idempotency-Key", chave())
                .body(Map.of("pagadorContaId", joao.id().toString(), "copiaECola", semValor))
                .post("/v1/transferencias").then().statusCode(422);
        given().contentType(ContentType.JSON).header("Idempotency-Key", chave())
                .body(Map.of("pagadorContaId", joao.id().toString(), "copiaECola", semValor, "valor", "7.00"))
                .post("/v1/transferencias").then().statusCode(201).body("valor", equalTo("7.00"));
    }

    @Test
    @DisplayName("QR Code adulterado: 400 qrcode-invalido, e nenhum dinheiro se move")
    void adulterado() {
        Conta joao = novaConta("João", "100.00");
        Conta maria = novaConta("Maria", "0.00");
        String email = chaveEmail(maria);
        String original = given().queryParam("valor", "10.00").get("/v1/chaves/{c}/qrcode", email).then().extract().path("copiaECola");
        String adulterado = original.replace("540510.00", "540590.00");

        given().contentType(ContentType.JSON).body(Map.of("copiaECola", adulterado))
                .post("/v1/qrcodes/leitura").then()
                .statusCode(400).body("type", equalTo("urn:ledgerly:erro:qrcode-invalido"));
        given().contentType(ContentType.JSON).header("Idempotency-Key", chave())
                .body(Map.of("pagadorContaId", joao.id().toString(), "copiaECola", adulterado))
                .post("/v1/transferencias").then()
                .statusCode(400).body("type", equalTo("urn:ledgerly:erro:qrcode-invalido"));
        assertThat(ServicosFalsos.RAZAO.lancamentos()).isZero();
    }

    @Test
    @DisplayName("a imagem SVG do QR Code")
    void svg() {
        Conta maria = novaConta("Maria", "0.00");
        String email = chaveEmail(maria);
        given().get("/v1/chaves/{c}/qrcode.svg", email).then()
                .statusCode(200)
                .contentType("image/svg+xml")
                .body(startsWith("<svg"), containsString("<path"));
        given().get("/v1/chaves/{c}/qrcode", "ninguem@exemplo.com").then().statusCode(404);
        given().queryParam("valor", "dez").get("/v1/chaves/{c}/qrcode", email).then().statusCode(400);
    }
}
