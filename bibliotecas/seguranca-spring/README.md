# Biblioteca seguranca-spring

A segurança que os três serviços Spring (contas, razão e extrato) têm em comum: conferir o token
do Keycloak, dizer quem chamou e pedir o token do próprio serviço para chamar outro. Cada serviço
só escreve as **próprias regras por papel**. O Pix, em Quarkus, faz o mesmo com a extensão OIDC do
Quarkus ([ADR-0024](../../docs/adr/0024-keycloak-e-realm-como-codigo.md)).

**Stack:** Java 25 · Spring Security 7 (OAuth2 Resource Server e OAuth2 Client) · Nimbus JOSE + JWT

## O que entra num serviço

| Peça | O que faz |
|---|---|
| [SegurancaAutoConfiguracao](src/main/java/io/github/alanjoffre/ledgerly/seguranca/SegurancaAutoConfiguracao.java) | Liga sozinha (*auto-configuration*): o decodificador de token apontado para o Keycloak (`ledgerly.seguranca.emissor` e `ledgerly.seguranca.audiencia`) |
| [Decodificadores](src/main/java/io/github/alanjoffre/ledgerly/seguranca/Decodificadores.java) | Confere a **assinatura** (chave pública do JWKS do emissor), a **validade** (`exp` e `nbf`, com 60 s de tolerância de relógio), o **emissor** (`iss`) e a **audiência** (`aud` com `ledgerly-api`) |
| [ConversorDeToken](src/main/java/io/github/alanjoffre/ledgerly/seguranca/ConversorDeToken.java) | Lê os papéis em `realm_access.roles`, onde o Keycloak os põe (o padrão do Spring lê `scope`). Cada papel vira `ROLE_<papel>` |
| [Usuario](src/main/java/io/github/alanjoffre/ledgerly/seguranca/Usuario.java) | Quem pediu: `sub`, `preferred_username`, papéis e o `cliente_id` (o que liga o login ao cliente no serviço de contas). O controller recebe com `@AuthenticationPrincipal Usuario` |
| [SegurancaDeRecurso](src/main/java/io/github/alanjoffre/ledgerly/seguranca/SegurancaDeRecurso.java) | O que todo serviço repete: sem sessão (cada pedido traz o token), CSRF desligado (não há cookie; o CSRF é tratado no BFF), `/actuator/health` aberto, e as regras do serviço |
| [RespostasDeSeguranca](src/main/java/io/github/alanjoffre/ledgerly/seguranca/RespostasDeSeguranca.java) | `401 nao-autenticado` (com `WWW-Authenticate: Bearer`) e `403 acesso-negado`, em Problem Details, como todos os outros erros |
| [TokenDeServico](src/main/java/io/github/alanjoffre/ledgerly/seguranca/TokenDeServico.java) | O token do próprio serviço (*client credentials*, [ADR-0027](../../docs/adr/0027-token-de-servico-e-razao-sem-rota.md)), posto no `RestClient` |
| [Papeis](src/main/java/io/github/alanjoffre/ledgerly/seguranca/Papeis.java) | `cliente`, `analista` e `servico` |

Uso, no serviço:

```java
@Bean
SecurityFilterChain seguranca(HttpSecurity http) throws Exception {
    return SegurancaDeRecurso.configurar(http, regras -> regras
            .requestMatchers(GET, "/v1/contas/*/extrato").hasAnyRole(Papeis.CLIENTE, Papeis.ANALISTA, Papeis.SERVICO)
            .requestMatchers("/v1/**").denyAll());     // negar por padrão
}
```

O papel diz **o que** a pessoa pode fazer. **De quem** é a conta fica no caso de uso de cada
serviço, porque a regra por papel não sabe de quem é o `{id}` do caminho (BOLA,
[ADR-0026](../../docs/adr/0026-bola-com-404.md)).

### O token de serviço, com dois cuidados

O padrão do Spring para *client credentials* foi feito para uma aplicação web com gente logada, e
erra em dois pontos num serviço:

- ele guarda o token **na sessão HTTP** do pedido em andamento, e o serviço não tem sessão. Aqui, o
  gerente do token fica fora do pedido;
- ele guarda **um token por pessoa logada**. Com a Ana e o Bruno chamando, o contas pediria um token
  de serviço para cada um. O token é do serviço: um só, reaproveitado até vencer.

O token é pedido na primeira chamada, e não na subida: o serviço sobe mesmo com o Keycloak fora do
ar. Se o outro serviço responder `401` (o Keycloak trocou a chave, o token foi revogado), o token
guardado é descartado, e o próximo pedido busca um novo.

## Para os testes dos serviços

O *test-jar* da biblioteca leva o pacote `teste`, que os serviços usam nos testes de integração:

- [TokensDeTeste](src/test/java/io/github/alanjoffre/ledgerly/seguranca/teste/TokensDeTeste.java)
  assina tokens **de verdade** (RS256), com uma chave criada na memória. Por que não o `jwt()` do
  spring-security-test: ele pula o decodificador e o conversor, e o teste não pegaria um erro no
  caminho dos papéis, na audiência ou no emissor. Aqui o token passa pelos mesmos validadores da
  produção; só a chave pública vem da memória, e o Keycloak não precisa estar no ar;
- [MockMvcComToken](src/test/java/io/github/alanjoffre/ledgerly/seguranca/teste/MockMvcComToken.java)
  faz o `MockMvc` mandar um token padrão, para os testes das Fases 1 a 3 continuarem como eram. O
  teste que quer outro token manda o próprio cabeçalho.

## Testes

```bash
./mvnw -pl bibliotecas/seguranca-spring verify     # não precisa de Docker
```

`SegurancaDeRecursoTest`, `ConversorDeTokenTest`, `UsuarioTest`, `TokenDeServicoTest` e
`SegurancaAutoConfiguracaoTest`: token vencido, de outro emissor, de outra audiência ou forjado;
papéis aninhados; `cliente_id` que não é UUID; o token de serviço reaproveitado e descartado no
`401`. Cobertura: **100% das linhas, 93,8% dos ramos**.
