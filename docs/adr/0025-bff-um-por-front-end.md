# ADR-0025: Um BFF por front-end, com o token fora do navegador

- **Status:** Aceita
- **Data:** 2026-10-28

## Contexto

A Fase 4 traz duas telas: o **internet banking** (cliente) e o **backoffice de fraude** (analista).
Uma tela no navegador que fala com uma API protegida por OAuth precisa do token de acesso. Onde ele
fica?

- **No navegador** (o modelo "SPA pública": o JavaScript faz o login e guarda o token na memória ou
  no `localStorage`). Qualquer XSS — um script injetado — lê o token e o usa de outro lugar. É o
  caminho que o guia de segurança do OAuth para apps de navegador (*OAuth 2.0 for Browser-Based
  Apps*, IETF) desaconselha para dado sensível.
- **No servidor**, num **BFF** (*Backend for Frontend*): um servidor pequeno, na mesma origem da
  tela, faz o login, guarda o token na sessão e só entrega à tela um cookie de sessão `HttpOnly`
  (o JavaScript não lê). A cada chamada, o BFF pega o token da sessão e repassa ao serviço.

## Decisão

Um módulo `servicos/gateway` (Spring Cloud Gateway Server WebMVC, ADR-0028) rodando como **dois
BFFs**, um por front-end:

| Perfil | Porta | Cookie | Cliente OIDC | Papel exigido em `/api/**` |
| --- | --- | --- | --- | --- |
| `internet-banking` | 8080 | `LEDGERLY_IB` | `ledgerly-internet-banking` | `cliente` |
| `backoffice` | 8090 | `LEDGERLY_BO` | `ledgerly-backoffice` | `analista` |

- **Login:** *authorization code* + **PKCE** (o BFF prova que é ele quem trocou o código pelo
  token). Os endereços do Keycloak são explícitos, sem descoberta na subida: o gateway sobe mesmo
  com o Keycloak fora do ar.
- **TokenRelay:** cada rota de `/api/**` remove o `Cookie` e o cabeçalho de CSRF (o serviço não
  precisa deles) e põe o `Authorization: Bearer` da sessão. O token vence em 5 minutos; o BFF o
  renova sozinho com o *refresh token*.
- **O razão não tem rota** (ADR-0027): só contas, extrato e pix são alcançáveis pela tela.
- **CSRF de SPA:** cookie `XSRF-TOKEN` legível + cabeçalho `X-XSRF-TOKEN` em todo pedido que muda
  algo. Um site de fora não lê o cookie, então não forja o POST.
- **Erros:** sem sessão, `/api/**` responde **401** em *problem details*; papel errado ou CSRF
  faltando, **403**, também em JSON — escritos pelo próprio filtro, sem passar por `/error`.
  A tela decide mandar ao login. Nenhum pedido fica guardado para "depois do login".
- **Sair:** apaga a sessão aqui e encerra a do Keycloak (*RP-initiated logout*).
- **A tela** (o build do Vite) é servida pelo próprio BFF, com *fallback* de SPA (um endereço da
  tela, como `/contas/123`, devolve o `index.html`). Mesma origem: sem CORS.
- **Cabeçalhos:** CSP (`default-src 'self'`, nada de script embutido), `frame-ancestors 'none'`,
  `Referrer-Policy: no-referrer`, cookie `SameSite=Lax`.

**Por que dois e não um:** o cookie do navegador não separa por porta. Num BFF só, a sessão da
analista e a do cliente seriam o mesmo cookie, e uma pessoa com os dois papéis teria as duas telas
na mesma sessão. Dois BFFs dão duas sessões, dois clientes OIDC e duas regras de papel — e um
problema no backoffice não derruba o internet banking.

## O que os testes de ponta a ponta acharam

O E2E com navegador de verdade (ADR-0029) achou três defeitos que os testes de unidade não viam,
cada um corrigido com um teste novo:

1. um **403 virava 200 com a tela**: o Spring encaminha o erro para `/error`, e o *fallback* de SPA
   respondia ali. Agora o 403 de `/api/**` é escrito direto, e `/error` nunca vira tela;
2. um 401 de `/api/eu` ficava **guardado na sessão**, e o login terminava num JSON em vez da tela.
   Agora nenhum pedido é guardado (`NullRequestCache`);
3. a tela de Pix mandava a **chave mascarada** (`b***@...`) que a consulta devolve, e o Pix era
   recusado. Agora ela manda a chave digitada.

Os outros dois achados, fora do BFF, estão na ADR-0029.

## Números

Quanto custa o BFF no caminho? `desempenho/carga/medir-gateway.sh` lê a mesma conta (contas →
razão, com o saldo) por 30 s, com 16 clientes simultâneos, de dois jeitos: **direto** no contas, com
o token de serviço, e **pelo BFF**, com o cookie de uma sessão de verdade (o BFF acha a sessão,
troca o cookie pelo token e repassa). Mesma máquina (16 núcleos), uma rodada de cada, em 28/10:

| Leitura de conta, 16 clientes, 30 s | Direto no contas | Pelo BFF |
| --- | --- | --- |
| Pedidos por segundo | 1.844 | **1.685** (−9%) |
| Latência p50 | 7 ms | **8 ms** |
| Latência p99 | 18 ms | **16 ms** |
| Erros | 0 de 64.523 | 0 de 58.967 |

O BFF custa cerca de **1 ms por pedido** e 9% da vazão — o preço de um salto a mais na rede local e
da sessão. O p99 menor pelo BFF é variação entre rodadas, não ganho: o que se tira daqui é que o
custo é pequeno perto do resto do caminho. Os resumos estão em `desempenho/resultados/carga/`
(`gateway-direto.txt` e `gateway-bff.txt`).

## Alternativas consideradas

- **Token no navegador (SPA pública com PKCE)** — o mais simples, sem servidor no meio; mas o token
  fica ao alcance de um XSS. Num banco, o custo do salto a mais compensa.
- **Um BFF só, com as duas telas** — uma sessão para os dois papéis; ver acima.
- **API Gateway "puro" (validar token e rotear)** — não resolve onde o token mora; é o papel que os
  próprios serviços já cumprem (cada um valida o JWT).

## Consequências

- **Positivas:** o JavaScript nunca vê um token; o serviço continua recebendo um JWT e confere tudo
  sozinho (defesa em profundidade: o BFF barra o papel, o serviço barra o papel *e* o dono); a tela
  fica simples (sem biblioteca de OAuth no navegador).
- **Negativas:** um salto de rede a mais em todo pedido (medido acima); a sessão fica na memória do
  BFF — mais de uma instância exigiria sessão compartilhada (Spring Session + Redis); mais um
  processo para subir (dois, na verdade).
