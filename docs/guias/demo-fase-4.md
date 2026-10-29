# Guia: demonstração da Fase 4

Mostra, na sua máquina, as duas telas do banco com login de verdade:

- no **internet banking**, a Ana vê as contas e o saldo, envia um Pix para o Bruno conferindo quem
  recebe e encontra o Pix no extrato, com a mensagem;
- no **backoffice**, a analista Carla devolve um Pix que ficou travado em análise.

Depois, os testes de ponta a ponta fazem tudo isso sozinhos num navegador, em cerca de 5 segundos.
Leva uns 10 minutos (a maior parte é compilar).

O que está por trás: o login no Keycloak ([ADR-0024](../adr/0024-keycloak-e-realm-como-codigo.md)),
um BFF por tela com o token fora do navegador ([ADR-0025](../adr/0025-bff-um-por-front-end.md)), a
conta de outro respondendo 404 ([ADR-0026](../adr/0026-bola-com-404.md)) e a devolução
([ADR-0030](../adr/0030-devolucao-de-pix-em-analise.md)).

## 1. Subir tudo

Na raiz do projeto (pré-requisitos no [guia de ambiente local](ambiente-local.md), agora com o
**Node 22** para as telas):

```bash
docker compose -f infra/docker/compose.yaml up -d --wait   # bancos, Kafka e Keycloak (com o realm)
./mvnw package -DskipTests                                 # os pacotes Java
(cd web && npm ci && npm run construir)                    # as duas telas
./scripts/servicos.sh subir                                # os quatro serviços e os dois gateways
./scripts/semear.sh                                        # conta, saldo e chave Pix para a Ana e o Bruno
```

A semeadura cadastra a Ana e o Bruno no serviço de contas (com um CPF gerado na hora, que não é de
ninguém), abre uma conta para cada, deposita R$ 1.000,00, registra a chave Pix de e-mail e grava o
`cliente_id` no usuário do Keycloak. É esse `cliente_id`, dentro do token, que liga o login à conta.
Pode rodar de novo: quem já tem conta fica como está.

| Usuário | Senha (só local) | Papel | Tela |
|---|---|---|---|
| `ana` | `ana-local` | cliente | http://localhost:8080 |
| `bruno` | `bruno-local` | cliente | http://localhost:8080 |
| `carla` | `carla-local` | analista | http://localhost:8090 |

## 2. O internet banking

Abra http://localhost:8080 e clique em **Entrar**. O navegador vai à página do Keycloak
(`localhost:18081`): a senha é digitada lá, e não na tela do banco. Entre como `ana`.

1. **Minhas contas** mostra as contas da Ana, cada uma com o saldo (R$ 1.000,00 na primeira vez).
   As contas encerradas que aparecerem são as que o script do backoffice (passo 3) usou.
2. **Enviar Pix**: a chave do Bruno é `bruno@exemplo.test`. Escreva um valor e uma mensagem
   ("Aluguel") e clique em **Continuar**. A tela mostra **para quem** vai o dinheiro (o nome e a
   chave mascarada, `b***@...`) antes de enviar. É o "confira quem recebe" dos bancos de verdade.
3. Clique em **Confirmar Pix**. A tela acompanha o Pix até "Pix enviado.".
4. Abra o **extrato** da conta: a linha do Pix está lá, com a mensagem. Pode levar um instante: o
   extrato é montado por eventos (CQRS) e chega uns 100 ms depois.

**Onde está o token?** Abra as ferramentas do navegador (F12) → Aplicação → Cookies. Há só o
`LEDGERLY_IB`, marcado como `HttpOnly` (o JavaScript não lê), e o `XSRF-TOKEN` (o do CSRF). O
token de acesso nunca chegou ao navegador: ficou no gateway, que o põe em cada pedido aos serviços.

**BOLA, à mão.** Ainda como Ana, abra `http://localhost:8080/api/v1/contas/<um UUID qualquer>`: a
resposta é `404 conta-nao-encontrada`. A conta do Bruno dá **exatamente a mesma resposta**: a Ana
não consegue nem descobrir se ela existe.

**Sair** encerra a sessão no gateway e no Keycloak. Clicar em Entrar de novo pede a senha outra vez.

## 3. O backoffice

Um Pix fica **em análise** quando o estorno é recusado: a conta que pagou foi encerrada no meio do
caminho, e o dinheiro está parado na Liquidação Pix. Pela tela não há como provocar isso (débito,
crédito e estorno acontecem no mesmo pedido), então um script monta o caso com o razão e o contas de
verdade:

```bash
./scripts/pix-em-analise.sh               # a Ana paga R$ 300,00 ao Bruno; imprime o id do Pix
./scripts/pix-em-analise.sh ana bruno 45.67
```

O script abre uma conta nova para a Ana, deposita o valor, registra o envio do Pix no razão,
encerra a conta (agora com saldo zero) e grava a transferência como `EM_ANALISE`.

Abra http://localhost:8090 e entre como `carla`:

1. A **fila de análise** mostra o Pix, com o valor, quem pagou e o motivo.
2. Abra o Pix. A tela oferece as contas **ativas da mesma titular** para receber a devolução. A
   justificativa é obrigatória (pelo menos 10 letras): o botão **Devolver** só se acende com ela.
3. Devolva. O Pix sai da fila. A decisão (quem, quando e por quê) fica numa tabela que nunca é
   alterada nem apagada, e o extrato da Ana mostra a volta na outra conta.

Tente também entrar no backoffice como `ana`: a tela diz "Acesso negado: o backoffice é só da
equipe de análise.", e a API responde `403`.

## 4. Os testes de ponta a ponta

Com tudo no ar e semeado:

```bash
cd web/e2e
npx playwright install chromium   # na primeira vez: baixa o navegador
npm run e2e
```

```text
  ✓  1 testes/backoffice.spec.ts:5:3 › backoffice (analista) › a Carla vê o Pix em análise na fila, devolve ao titular e ele sai da fila (1.6s)
  ✓  2 testes/backoffice.spec.ts:31:3 › backoffice (analista) › uma cliente não entra no backoffice (392ms)
  ✓  3 testes/internet-banking.spec.ts:5:3 › internet banking (cliente) › a Ana entra, envia um Pix para a chave do Bruno e vê no extrato (1.0s)
  ✓  4 testes/internet-banking.spec.ts:37:3 › internet banking (cliente) › BOLA: a Ana não enxerga a conta do Bruno nem pela API (404, como se não existisse) (1.1s)
  ✓  5 testes/internet-banking.spec.ts:67:3 › internet banking (cliente) › sem sessão a API responde 401, e Sair encerra a sessão também no Keycloak (635ms)
  5 passed (5.4s)
```

Uma armadilha que apareceu ao preparar este guia: recompilar um serviço (`./mvnw package` ou
`verify`) **com ele no ar** regrava o `.jar` por baixo do processo, e ele passa a falhar com
`NoClassDefFoundError` (no log, "O Kafka não confirmou o lote"). Depois de recompilar, suba o
serviço de novo: `./scripts/servicos.sh parar razao && ./scripts/servicos.sh subir razao`. Nada se
perde: os eventos esperam no Outbox e saem quando ele volta.

O Playwright abre um Chromium de verdade, digita o usuário e a senha na página do Keycloak, clica e
confere o que aparece. Nada é simulado. Esses cinco testes acharam **cinco defeitos** que os
outros 839 deixaram passar; a lista está na
[ADR-0029](../adr/0029-react-vite-vitest-playwright.md). Num erro, o relatório com o rastro de cada
passo fica em `web/e2e/playwright-report/` (`npx playwright show-report`).

## 5. Medir o custo do BFF (opcional)

```bash
desempenho/carga/medir-gateway.sh          # 16 usuários por 30 s, direto no contas e pelo gateway
```

A mesma leitura de conta, com a mesma carga, nos dois caminhos. A sessão vem de um login de verdade
pelo navegador ([sessao.mjs](../../web/e2e/sessao.mjs)). Na medição da
[ADR-0025](../adr/0025-bff-um-por-front-end.md), o BFF custou cerca de 1 ms por pedido (9% menos
vazão), com zero erros nos dois.

## 6. Desligar

```bash
./scripts/servicos.sh parar
docker compose -f infra/docker/compose.yaml down        # os dados ficam; -v apaga tudo
```
