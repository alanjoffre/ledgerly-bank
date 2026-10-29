package io.github.alanjoffre.ledgerly.pix.integracao;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwk.RsaJwkGenerator;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.lang.JoseException;

/**
 * Tokens de teste do Pix, assinados de verdade (RS256) por uma chave RSA criada na memória — como
 * o {@code TokensDeTeste} dos serviços Spring, mas com o jose4j, que já vem com o quarkus-oidc.
 *
 * <p>O emissor falso ({@link ServicosFalsos}) publica a chave pública (JWKS) e o endereço de
 * descoberta; o Pix confere o token pelo mesmo caminho da produção — assinatura, emissor, validade,
 * audiência e papéis em {@code realm_access.roles}.
 */
public final class TokensPix {

    static final String AUDIENCIA = "ledgerly-api";

    private static final RsaJsonWebKey CHAVE = novaChave("chave-de-teste");
    private static final RsaJsonWebKey OUTRA_CHAVE = novaChave("chave-de-outro");
    private static volatile String emissor = "http://emissor.invalido/realms/ledgerly";

    private TokensPix() {}

    static void emissor(String valor) {
        emissor = valor;
    }

    static String emissor() {
        return emissor;
    }

    /** O JWKS que o emissor falso publica: só a chave pública de teste. */
    static String jwks() {
        return new JsonWebKeySet(CHAVE).toJson(org.jose4j.jwk.JsonWebKey.OutputControlLevel.PUBLIC_ONLY);
    }

    public static String servico() {
        return token().nome("service-account-ledgerly-teste").papeis("servico").bearer();
    }

    public static String cliente(UUID clienteId) {
        return token().nome("cliente-teste").papeis("cliente").clienteId(clienteId).bearer();
    }

    public static String analista() {
        return token().nome("analista-teste").papeis("analista").bearer();
    }

    public static Construtor token() {
        return new Construtor();
    }

    /** O token chegou assinado pela chave de teste, com o papel {@code servico}? (o razão e as contas falsos perguntam) */
    static boolean ehDeServico(String autorizacao) {
        if (autorizacao == null || !autorizacao.startsWith("Bearer ")) {
            return false;
        }
        try {
            var claims = new JwtConsumerBuilder()
                    .setVerificationKey(CHAVE.getPublicKey())
                    .setExpectedIssuer(emissor)
                    .setExpectedAudience(AUDIENCIA)
                    .build()
                    .processToClaims(autorizacao.substring("Bearer ".length()));
            Object papeis = claims.getClaimValue("realm_access", Map.class).get("roles");
            return papeis instanceof List<?> lista && lista.contains("servico");
        } catch (InvalidJwtException | org.jose4j.jwt.MalformedClaimException e) {
            return false;
        }
    }

    /** Um token fora do padrão: vencido, de outro emissor, de outra audiência, outra chave. */
    public static final class Construtor {

        private String sujeito = UUID.randomUUID().toString();
        private String nome = "teste";
        private List<String> papeis = List.of();
        private String clienteId;
        private String emissorDoToken = emissor;
        private List<String> audiencia = List.of(AUDIENCIA);
        private float minutosParaVencer = 5;
        private RsaJsonWebKey chave = CHAVE;

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

        public Construtor clienteIdTexto(String valor) {
            clienteId = valor;
            return this;
        }

        public Construtor emissor(String valor) {
            emissorDoToken = valor;
            return this;
        }

        public Construtor audiencia(String... valores) {
            audiencia = List.of(valores);
            return this;
        }

        public Construtor vencido() {
            minutosParaVencer = -10;
            return this;
        }

        public Construtor assinadoPorOutraChave() {
            chave = OUTRA_CHAVE;
            return this;
        }

        public String bearer() {
            return "Bearer " + valor();
        }

        public String valor() {
            JwtClaims claims = new JwtClaims();
            claims.setIssuer(emissorDoToken);
            claims.setSubject(sujeito);
            claims.setAudience(audiencia);
            claims.setIssuedAtToNow();
            claims.setExpirationTimeMinutesInTheFuture(minutosParaVencer);
            claims.setClaim("preferred_username", nome);
            claims.setClaim("realm_access", Map.of("roles", papeis));
            if (clienteId != null) {
                claims.setClaim("cliente_id", clienteId);
            }
            JsonWebSignature assinatura = new JsonWebSignature();
            assinatura.setPayload(claims.toJson());
            assinatura.setKey(chave.getPrivateKey());
            assinatura.setKeyIdHeaderValue(chave.getKeyId());
            assinatura.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
            try {
                return assinatura.getCompactSerialization();
            } catch (JoseException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static RsaJsonWebKey novaChave(String id) {
        try {
            RsaJsonWebKey chave = RsaJwkGenerator.generateJwk(2048);
            chave.setKeyId(id);
            chave.setAlgorithm(AlgorithmIdentifiers.RSA_USING_SHA256);
            chave.setUse("sig");
            return chave;
        } catch (JoseException e) {
            throw new IllegalStateException(e);
        }
    }
}
