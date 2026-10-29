# Telas (web)

As duas telas do Ledgerly Bank, em **React 19 + TypeScript**, construídas com **Vite** e testadas
com **Vitest** (unidade) e **Playwright** (ponta a ponta). O porquê de cada escolha está na
[ADR-0029](../docs/adr/0029-react-vite-vitest-playwright.md).

| Pacote | O que é | Quem usa | Endereço |
|---|---|---|---|
| [internet-banking](internet-banking/) | O banco do cliente: contas e saldo, extrato, Pix (com "confira quem recebe"), chaves Pix | cliente | http://localhost:8080 |
| [backoffice](backoffice/) | O backoffice de fraude: a fila de Pix em análise, a devolução com justificativa, a consulta e o bloqueio de contas | analista | http://localhost:8090 |
| [comum](comum/) | O que as duas usam: as chamadas à API (com o CSRF), quem está logado, sair, dinheiro em reais | — | — |
| [e2e](e2e/) | Os testes de ponta a ponta, num navegador de verdade | — | — |

As quatro pastas são *workspaces* do npm: um `npm ci` aqui instala tudo, com as versões exatas do
`package-lock.json`.

## Como a tela fala com o banco

A tela **não tem token nenhum**. Ela é servida pelo próprio gateway (o BFF), na mesma origem que
a API, e chama `/api/v1/...` com o cookie de sessão, que o JavaScript nem consegue ler
([gateway](../servicos/gateway/README.md), [ADR-0025](../docs/adr/0025-bff-um-por-front-end.md)).

- **Quem sou eu**: ao abrir, a tela pede `GET /api/eu`. Com `401`, mostra o botão **Entrar**, que
  leva ao login no Keycloak (`/oauth2/authorization/keycloak`). A pessoa sem o papel desta tela vê
  "este acesso não é seu", e nada mais é pedido.
- **CSRF**: todo POST e DELETE leva o cabeçalho `X-XSRF-TOKEN`, copiado do cookie `XSRF-TOKEN`
  ([comum/src/api.ts](comum/src/api.ts)).
- **Erros**: a API responde em Problem Details, e a tela decide pelo `type`, nunca pelo texto. Um
  `404` de conta de outro cliente aparece como "não encontrada", igual a uma conta que não existe.
- **Pix**: a chave de idempotência nasce no primeiro envio, e tentar de novo depois de uma falha
  reusa a **mesma** chave. Assim um clique repetido não vira dois Pix.
- **Sair**: `POST /logout`, e a tela vai ao endereço de saída do Keycloak que o gateway devolve; a
  sessão acaba nos dois.

## Rodar

```bash
npm ci                 # instala (Node 22 ou mais novo)
npm run construir      # gera internet-banking/dist e backoffice/dist, que os gateways servem
```

Com a infraestrutura, os serviços e os gateways no ar (passo a passo na
[demonstração da Fase 4](../docs/guias/demo-fase-4.md)), as telas abrem em
http://localhost:8080 e http://localhost:8090. As pessoas do ambiente local (realm em
[ledgerly-realm.json](../infra/docker/keycloak/ledgerly-realm.json), senhas só de ambiente local):

| Usuário | Senha | Papel | Tela |
|---|---|---|---|
| `ana` | `ana-local` | cliente | internet banking |
| `bruno` | `bruno-local` | cliente | internet banking |
| `carla` | `carla-local` | analista | backoffice |

Para a Ana e o Bruno terem conta, saldo e chave Pix, rode antes `./scripts/semear.sh` (na raiz).

**Modo de desenvolvimento** (recarrega a cada mudança): `npm run dev -w internet-banking` (porta
5173) ou `npm run dev -w backoffice` (5174). O Vite repassa ao gateway `/api`, `/oauth2`, `/login`
e `/logout`, e o gateway precisa estar no ar.

## Testes

```bash
npm run tipos       # o TypeScript confere os tipos (sem gerar nada)
npm run testar      # Vitest + Testing Library
npm run verificar   # tipos, testes e construção: o que o CI roda
```

| Pacote | Testes | O que prova |
|---|---|---|
| comum | 27 | As chamadas à API: CSRF no POST, Problem Details, 401, sair; dinheiro em reais, sem perder centavo |
| internet-banking | 16 | Entrar, quem não é cliente, banco fora do ar; contas com o saldo de cada uma; extrato com "carregar mais"; o Pix confere quem recebe, envia e acompanha até concluir, **repete com a mesma chave de idempotência**, diz quando fica em análise; chaves |
| backoffice | 14 | Cliente não entra; a fila; a devolução com justificativa, outra analista decidiu antes (409), titular sem conta ativa; consulta, bloqueio e desbloqueio |

Os testes de unidade trocam o `fetch` por um falso. É rápido, mas prova a tela contra o falso, e
não contra a API. Dois defeitos da Fase 4 passaram por aí: o falso trazia o saldo na lista de
contas, e a API real não traz.

### Testes de ponta a ponta (Playwright)

Um Chromium de verdade contra o sistema de verdade: Keycloak, os dois gateways e os quatro
serviços. Ele digita o usuário e a senha na página do Keycloak, clica e confere o que a pessoa vê.

```bash
# na raiz, com tudo no ar e semeado (ver a demonstração da Fase 4):
cd web/e2e
npx playwright install chromium   # na primeira vez: baixa o navegador
npm run e2e                       # cerca de 5 s
```

| Teste | O que prova |
|---|---|
| internet banking | A Ana entra, envia um Pix para a chave do Bruno, e o Pix aparece no extrato **com a mensagem**; o saldo cai o valor exato |
| internet banking | **BOLA**: a Ana pede a conta do Bruno direto na API e recebe 404, como se ela não existisse |
| internet banking | Sem sessão, a API responde 401; Sair encerra a sessão também no Keycloak |
| backoffice | A Carla vê o Pix em análise na fila, devolve ao titular, e ele sai da fila |
| backoffice | Uma cliente não entra no backoffice |

Os testes rodam um por vez (`workers: 1`): mexem no saldo das mesmas contas. Num erro, o
Playwright guarda o rastro (`test-results/`) e o relatório (`playwright-report/`), que ficam fora do
Git.

Os testes de ponta a ponta acharam **cinco defeitos** que todos os outros testes deixaram passar.
A lista e a lição estão na [ADR-0029](../docs/adr/0029-react-vite-vitest-playwright.md).

**Ainda fora do CI**: eles precisam do sistema inteiro no ar (Keycloak, Kafka, três bancos e seis
processos Java). O CI roda os tipos, os testes de unidade e a construção.
