# ADR-0029: Telas em React + TypeScript + Vite, testadas com Vitest e Playwright

- **Status:** Aceita
- **Data:** 2026-10-28

## Contexto

As duas telas da Fase 4 (internet banking e backoffice) precisam de uma pilha de front-end. O
projeto é de back-end: a tela existe para mostrar o sistema funcionando de ponta a ponta e para ser
testada como uma pessoa usaria. Ela precisa ser leve, segura (CSP estrita, ADR-0025) e testável.

## Decisão

- **React 19 + TypeScript 7 + react-router 8**, construído pelo **Vite 8**, em `web/` com *npm
  workspaces*: `comum` (cliente da API, dinheiro, estilo), `internet-banking`, `backoffice` e `e2e`.
- **TypeScript estrito** (`strict`, `noUncheckedIndexedAccess`): um índice de array pode ser
  `undefined`, e o compilador obriga a tratar.
- **Dinheiro como texto** do começo ao fim (ADR-0012): a tela formata com `BigInt`, sem `float`.
  Um valor digitado "1.234,56" vira "1234.56" antes de ir para a API.
- **Sem biblioteca de componentes**: CSS próprio com *tokens* (cores para tema claro e escuro). A CSP
  proíbe estilo embutido, e um kit de UI traria dezenas de KB para duas telas.
- **Idempotência na tela:** a chave do Pix (`crypto.randomUUID()`) nasce ao conferir e é a mesma em
  toda nova tentativa (ADR-0010) — se a rede cair depois do clique, tentar de novo não envia duas vezes.
- **Testes de unidade: Vitest + Testing Library** (com jsdom), procurando os elementos como uma
  pessoa — pelo papel e pelo nome acessível ("botão Confirmar Pix"), não por classe CSS. O `fetch`
  é trocado por um falso que só responde o que o teste previu.
- **Testes de ponta a ponta: Playwright** com Chromium de verdade, contra o sistema inteiro no ar
  (Keycloak, os dois BFFs e os quatro serviços): login na página do Keycloak, Pix até concluir,
  extrato, BOLA pelo BFF, saída, fila de análise e devolução. Um teste por vez (`workers: 1`): os
  testes mexem nas mesmas contas.
- **No CI:** tipos, Vitest e o build das duas telas (job `web`). O E2E roda na máquina, antes do
  envio: ele precisa do sistema inteiro no ar (sete processos e seis contêineres).

## O que os testes de ponta a ponta acharam

Cinco defeitos que passavam em todos os testes de unidade e de integração. Os três do BFF estão na
ADR-0025. Os outros dois têm a mesma causa: **o falso do teste não era igual à API real**.

1. **A mensagem do Pix nunca chegava ao extrato** (defeito da Fase 2). A Saga mandava ao razão o
   texto fixo "Pix para Bruno Exemplo", e o extrato mostra o texto do lançamento: o "Aluguel" de
   quem pagou se perdia. Agora o débito e o crédito levam a mensagem; sem mensagem, quem é a outra
   ponta. O razão falso dos testes do pix passou a guardar a descrição recebida, e o teste confere.
2. **"Minhas contas" mostrava "Saldo indisponível agora" em toda conta.** A lista de contas do
   cliente vem sem saldo desde a Fase 1 (para não consultar o razão uma vez por conta), mas o falso
   do Vitest trazia o saldo na lista. Agora a tela busca o saldo de cada conta em
   `GET /v1/contas/{id}`, em paralelo, e o falso responde como a API.

A lição: o teste com falso prova a tela contra o falso. Só o E2E, contra os serviços de verdade,
prova a tela contra a API — por isso ele roda antes de cada envio, mesmo fora do CI.

## Alternativas consideradas

- **Angular** — muito usado em banco, mas pesado para duas telas e traz o próprio ecossistema
  (RxJS, módulos) a aprender junto.
- **Next.js** — renderização no servidor e rotas no próprio framework; aqui o servidor já é o BFF
  em Java, e um segundo servidor (Node) seria mais um processo e mais uma superfície.
- **Jest** — o Vitest usa a mesma configuração do Vite (sem um segundo *bundler* só para teste) e
  tem a mesma API.
- **Cypress** — bom, mas o Playwright roda vários navegadores, abre mais de um contexto no mesmo
  teste (a Ana e o Bruno logados ao mesmo tempo, no teste de BOLA) e é mais rápido.

## Consequências

- **Positivas:** telas pequenas (~85 KB de JavaScript com gzip cada); os testes de unidade rodam em
  segundos; o E2E achou cinco defeitos reais que nenhum outro teste via (três na ADR-0025, dois
  acima).
- **Negativas:** um segundo ecossistema no repositório (Node, npm, `package-lock.json`); o E2E não
  roda no CI e depende de quem envia rodá-lo; o React é dependência de terceiros que o Dependabot
  precisa acompanhar.
