package io.github.alanjoffre.ledgerly.seguranca;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.alanjoffre.ledgerly.seguranca.teste.TokensDeTeste;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@DisplayName("Configuração automática: o decodificador do Keycloak")
class SegurancaAutoConfiguracaoTest {

    private final ApplicationContextRunner contexto = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SegurancaAutoConfiguracao.class));

    @Test
    @DisplayName("cria o decodificador sem chamar o Keycloak na subida")
    void cria() {
        contexto.withPropertyValues("ledgerly.seguranca.emissor=http://kc.invalido/realms/ledgerly")
                .run(c -> assertThat(c).hasSingleBean(JwtDecoder.class)
                        .getBean(JwtDecoder.class).isInstanceOf(NimbusJwtDecoder.class));
    }

    @Test
    @DisplayName("aceita o endereço JWKS explícito")
    void jwksExplicito() {
        contexto.withPropertyValues("ledgerly.seguranca.jwks=http://kc.invalido/certs")
                .run(c -> assertThat(c).hasSingleBean(JwtDecoder.class));
    }

    @Test
    @DisplayName("sai de cena quando o teste traz o próprio decodificador")
    void respeitaODoTeste() {
        JwtDecoder doTeste = TokensDeTeste.decodificador();
        contexto.withBean(JwtDecoder.class, () -> doTeste)
                .run(c -> assertThat(c.getBean(JwtDecoder.class)).isSameAs(doTeste));
    }
}
