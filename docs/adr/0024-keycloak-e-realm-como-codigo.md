# ADR-0024: Keycloak como servidor de identidade, com o realm como código

- **Status:** Aceita
- **Data:** 2026-10-28

## Contexto

Até a Fase 3, qualquer um que alcançasse a porta de um serviço podia abrir conta, mandar Pix e ler
extrato: não havia login nem papel. Um banco precisa responder a três perguntas em todo pedido:

- **quem é** (autenticação): a Ana, o Bruno, a analista Carla, ou o serviço de Pix;
- **o que pode** (autorização por papel): cliente movimenta a própria conta, analista decide um Pix
  em análise, serviço chama outro serviço;
- **sobre o quê** (autorização por objeto, ADR-0026): a conta pedida é da pessoa que pede?

Escrever login, senha, sessão e emissão de token dentro do projeto seria o erro clássico: é o
código mais difícil de acertar e o mais atacado. O padrão de mercado é delegar a um servidor de
identidade que fala **OAuth 2.0** (o protocolo de autorização: quem pode usar o quê) e **OpenID
Connect** (OIDC, a camada de identidade em cima dele: quem é a pessoa). Ele emite **JWT** (*JSON Web
Token*): um texto assinado com a identidade e os papéis, que cada serviço confere sozinho, só com a
chave pública do emissor, sem perguntar a ninguém.

Restrição do projeto: custo zero e tudo rodando na máquina (ADR-0006).

## Decisão

Usar o **Keycloak 26.8.1** (código aberto, da Red Hat/CNCF) no Docker Compose, em
`127.0.0.1:18081`, com o **realm** (o "espaço" de usuários, papéis e clientes) **como código**:
`infra/docker/keycloak/ledgerly-realm.json`, importado na primeira subida (`--import-realm`).

- **Papéis do realm:** `cliente`, `analista` e `servico`. Os serviços leem `realm_access.roles` do
  token e viram `ROLE_cliente` etc. (biblioteca `bibliotecas/seguranca-spring`, e o `quarkus-oidc`
  no Pix).
- **Pessoas sintéticas:** ana e bruno (cliente), carla (analista), com senhas só locais.
- **O vínculo login → cliente** é o atributo `cliente_id` do usuário, que um *mapper* põe no token
  (claim `cliente_id`). No *user profile* do Keycloak ele é visível e editável só pelo admin: a
  pessoa não consegue mudar o próprio vínculo. Quem grava é a semeadura (`scripts/semear.sh`), pela
  API de administração.
- **Clientes OIDC:**

  | Cliente | Fluxo | Para quê |
  | --- | --- | --- |
  | `ledgerly-internet-banking` | *authorization code* + PKCE (S256) | o BFF do internet banking (ADR-0025) |
  | `ledgerly-backoffice` | *authorization code* + PKCE (S256) | o BFF do backoffice |
  | `ledgerly-contas`, `ledgerly-pix`, `ledgerly-extrato` | *client credentials* | um serviço chamando outro (ADR-0027) |
  | `ledgerly-ferramentas` | *client credentials* + gestão de usuários | scripts, semeadura e carga |

  Nenhum cliente tem *direct access grants* (a senha da pessoa digitada fora da tela do Keycloak):
  o login sempre acontece na página do Keycloak.
- **Token de acesso de 5 minutos**, audiência `ledgerly-api` (o serviço recusa token feito para
  outro sistema). Sessão de SSO de 30 minutos parada.
- **Emissor fixo** (`KC_HOSTNAME=http://localhost:18081`): o `iss` do token sai igual no navegador
  e nos serviços, que rodam fora do Docker. Se mudasse, o serviço recusaria o token.
- O CI sobe o Keycloak no job de infraestrutura e confere que o realm entrega um token de serviço.

## Alternativas consideradas

- **Login próprio (Spring Security com usuários no Postgres)** — é reinventar senha com *hash*,
  bloqueio por tentativa, sessão, MFA e rotação de chave. Seria a parte mais frágil do projeto.
- **Auth0, Okta, Cognito** — o padrão em nuvem, mas pago acima da cota grátis, preso a um
  fornecedor, e não roda na máquina nem no CI sem internet.
- **Spring Authorization Server** — bom para emitir token, mas não traz tela de login, *user
  profile*, console e federação; daria mais código para manter que o Keycloak em contêiner.
- **Realm configurado à mão no console** — some quando o volume é apagado e não passa por revisão.
  Como código, ele é versionado, revisado e igual em toda máquina.

## Consequências

- **Positivas:** nenhuma senha passa pelo código do banco; os serviços conferem o token sozinhos
  (assinatura + emissor + audiência + validade); mais um ponto de mercado coberto (OAuth 2.0, OIDC,
  PKCE, *client credentials*, JWT). Trocar o Keycloak por um gerenciado muda configuração, não código.
- **Negativas:** mais um contêiner (medido: ~970 MB de memória em uso, com o *heap* limitado a
  512 MB) e mais uma espera na subida da infraestrutura; o realm
  só é importado se ainda não existe — mudou o JSON, é preciso `down -v` (documentado no compose);
  os segredos dos clientes estão no JSON porque são **locais** (em nuvem viriam de um cofre, ADR-0006).
