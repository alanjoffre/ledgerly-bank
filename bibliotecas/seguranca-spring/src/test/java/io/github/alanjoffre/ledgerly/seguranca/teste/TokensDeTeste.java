package io.github.alanjoffre.ledgerly.seguranca.teste;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import io.github.alanjoffre.ledgerly.seguranca.Decodificadores;
import io.github.alanjoffre.ledgerly.seguranca.Papeis;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Tokens de teste assinados de verdade (RS256), com uma chave RSA criada na memória.
 *
 * <p>Por que não o {@code jwt()} do spring-security-test: ele pula o decodificador e o conversor,
 * e o teste não pegaria um erro no caminho dos papéis ({@code realm_access.roles}), na audiência ou
 * no emissor. Aqui o token passa pelos mesmos validadores da produção ({@link Decodificadores}); só
 * a chave pública vem da memória em vez do Keycloak.
 *
 * <p>Uso: o teste declara {@code @Bean JwtDecoder decodificador() { return TokensDeTeste.decodificador(); }}
 * e manda {@code Authorization: TokensDeTeste.cliente(id)}.
 */
public final class TokensDeTeste {

    public static final String EMISSOR = "https://emissor.teste/realms/ledgerly";
    public static final String AUDIENCIA = "ledgerly-api";

    private static final RSAKey CHAVE = novaChave("chave-de-teste");
    private static final RSAKey OUTRA_CHAVE = novaChave("chave-de-outro");

    private TokensDeTeste() {}

    /** O decodificador dos testes: a chave da memória e os validadores da produção. */
    public static JwtDecoder decodificador() {
        try {
            return Decodificadores.validar(
                    NimbusJwtDecoder.withPublicKey(CHAVE.toRSAPublicKey()).build(), EMISSOR, AUDIENCIA);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Token de outro serviço do Ledgerly (client credentials). */
    public static String servico() {
        return token().nome("service-account-ledgerly-teste").papeis(Papeis.SERVICO).bearer();
    }

    /** Cliente dono do {@code clienteId}. */
    public static String cliente(UUID clienteId) {
        return token().nome("cliente-teste").papeis(Papeis.CLIENTE).clienteId(clienteId).bearer();
    }

    public static String analista() {
        return token().nome("analista-teste").papeis(Papeis.ANALISTA).bearer();
    }

    public static Construtor token() {
        return new Construtor();
    }

    /** Monta um token fora do padrão: vencido, de outro emissor, de outra audiência, outra chave. */
    public static final class Construtor {

        private String sujeito = UUID.randomUUID().toString();
        private String nome = "teste";
        private List<String> papeis = List.of();
        private String clienteId;
        private String emissor = EMISSOR;
        private List<String> audiencia = List.of(AUDIENCIA);
        private Instant expiraEm = Instant.now().plus(Duration.ofMinutes(5));
        private RSAKey chave = CHAVE;

        public Construtor sujeito(String valor) {
            sujeito = valor;
            return this;
        }

        public Construtor nome(String valor) {
            nome = valor;
            return this;
        }

        public Construtor papeis(String... valores) {
            papeis = List.of(valores);
            return this;
        }

        public Construtor clienteId(UUID valor) {
            clienteId = valor.toString();
            return this;
        }

        /** Texto livre, para provar que um {@code cliente_id} que não é UUID não quebra nada. */
        public Construtor clienteIdTexto(String valor) {
            clienteId = valor;
            return this;
        }

        public Construtor emissor(String valor) {
            emissor = valor;
            return this;
        }

        public Construtor audiencia(String... valores) {
            audiencia = List.of(valores);
            return this;
        }

        public Construtor vencido() {
            expiraEm = Instant.now().minus(Duration.ofMinutes(10));
            return this;
        }

        /** Assinado por uma chave que o decodificador não conhece (um token forjado). */
        public Construtor assinadoPorOutraChave() {
            chave = OUTRA_CHAVE;
            return this;
        }

        public String bearer() {
            return "Bearer " + valor();
        }

        public String valor() {
            Map<String, Object> acesso = new HashMap<>();
            acesso.put("roles", papeis);
            var claims = JwtClaimsSet.builder()
                    .issuer(emissor)
                    .subject(sujeito)
                    .audience(audiencia)
                    .issuedAt(expiraEm.minus(Duration.ofMinutes(5)))
                    .expiresAt(expiraEm)
                    .claim("preferred_username", nome)
                    .claim("realm_access", acesso);
            if (clienteId != null) {
                claims.claim("cliente_id", clienteId);
            }
            var codificador = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(chave)));
            var cabecalho = JwsHeader.with(SignatureAlgorithm.RS256).keyId(chave.getKeyID()).build();
            return codificador.encode(JwtEncoderParameters.from(cabecalho, claims.build())).getTokenValue();
        }
    }

    private static RSAKey novaChave(String id) {
        try {
            return new RSAKeyGenerator(2048).keyID(id).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
