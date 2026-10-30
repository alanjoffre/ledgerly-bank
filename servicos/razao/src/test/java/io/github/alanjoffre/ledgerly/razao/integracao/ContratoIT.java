package io.github.alanjoffre.ledgerly.razao.integracao;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import io.github.alanjoffre.ledgerly.nucleo.Dinheiro;
import io.github.alanjoffre.ledgerly.razao.aplicacao.Comandos;
import io.github.alanjoffre.ledgerly.seguranca.teste.TokensDeTeste;
import java.util.Map;
import java.util.UUID;
import org.apache.hc.core5.http.HttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * O razão cumpre o que o Pix e o serviço de contas esperam dele (ADR-0045): cada interação dos arquivos
 * de {@code contratos/} é repetida contra o razão de verdade, com banco de verdade.
 */
@Provider("razao")
@PactFolder("../../contratos")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Contrato: o razão cumpre")
class ContratoIT extends TesteDeIntegracao {

    // As contas que os consumidores escreveram nos contratos.
    private static final UUID CONTA_DO_PIX = UUID.fromString("0a000000-0000-4000-8000-000000000001");
    private static final UUID CONTA_DE_CONTAS = UUID.fromString("0b000000-0000-4000-8000-000000000001");

    @LocalServerPort
    private int porta;

    @BeforeEach
    void alvo(PactVerificationContext contexto) {
        contexto.setTarget(new HttpTestTarget("localhost", porta));
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void confere(PactVerificationContext contexto, HttpRequest pedido) {
        pedido.setHeader("Authorization", TokensDeTeste.servico());
        contexto.verifyInteraction();
    }

    private void abrirComSaldo(UUID conta, String saldo) {
        contas.abrir(conta, Dinheiro.REAL);
        if (!saldo.equals("0.00")) {
            lancamentos.depositar(new Comandos.Deposito(chave(), conta, Dinheiro.reais(saldo), "Depósito do teste"));
        }
    }

    @State("a conta tem saldo")
    void pixComSaldo() {
        abrirComSaldo(CONTA_DO_PIX, "100.00");
    }

    @State("a conta está sem saldo")
    void pixSemSaldo() {
        abrirComSaldo(CONTA_DO_PIX, "0.00");
    }

    @State("já houve um envio de Pix")
    Map<String, Object> envioFeito() {
        abrirComSaldo(CONTA_DO_PIX, "100.00");
        var envio = lancamentos.enviarPix(new Comandos.MovimentoPix(chave(), CONTA_DO_PIX, Dinheiro.reais("30.00"), "Pix"));
        return Map.of("envioId", envio.lancamento().id().toString());
    }

    @State("a conta existe no razão com saldo")
    void contasComSaldo() {
        abrirComSaldo(CONTA_DE_CONTAS, "100.00");
    }

    @State("a conta existe no razão com saldo zero")
    void contasSemSaldo() {
        abrirComSaldo(CONTA_DE_CONTAS, "0.00");
    }

    @State("a conta não existe no razão")
    void contaInexistente() {
        // O banco já foi limpo antes do teste.
    }
}
