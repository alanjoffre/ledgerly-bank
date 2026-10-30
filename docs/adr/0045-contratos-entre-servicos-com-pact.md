# ADR-0045: Contratos entre serviços com Pact, em arquivos no repositório

- **Status:** Aceita
- **Data:** 2026-10-30

## Contexto

O Pix chama razão, contas e antifraude; o contas chama o razão. Cada serviço testa o outro com um dublê
escrito à mão. Renomear um campo no razão deixa os testes do razão verdes, os do Pix verdes (o dublê não
mudou), e quebra de verdade só com tudo no ar.

**Contrato guiado pelo consumidor:** quem chama grava o que usa de fato (rota, campos, tipos), e quem
responde confere esses arquivos no próprio build. O contrato é o que o consumidor lê, e não toda a API.

## Decisão

- **Pact JVM** 4.7.6, nos serviços que têm essas chamadas.
- **Arquivos em `contratos/`, versionados**, sem *broker* (o servidor de contratos do Pact), pelo custo zero
  ([ADR-0006](0006-nuvem-com-custo-zero.md)). Como é um *monorepo*, consumidor e provedor mudam no mesmo commit.
- **O CI confere com `git status --porcelain contratos`:** se um consumidor mudou o que espera, ou criou um
  contrato novo, e não versionou o arquivo, reprova. O `git diff` sozinho não enxergaria o arquivo novo.
- **Testes de integração reais no provedor:** o `ContratoIT` de cada um sobe o serviço de verdade, e não um dublê.

## Resultado

**12 interações:** contas → razão 4, Pix → razão 4, Pix → contas 3, Pix → antifraude 1. Todas verdes nos dois lados.

## Prova reversa

| Mudança no provedor | Resultado |
| --- | --- |
| `saldo` renomeado no razão | reprova |
| `nota` renomeado no antifraude | reprova |
| `situacao` renomeado no contas | reprova |
| `contaId` renomeado no titular (contas) | **não reprova, e está certo:** o Pix não lê esse campo |

A última linha é o motivo de usar contrato guiado pelo consumidor: um campo que ninguém lê pode mudar.

## O que ficou de fora

- **Extrato → contas** e **assistente → serviços bancários:** o risco é menor (um extrato ou uma resposta do
  assistente errada não move dinheiro), e o plano era cobrir o caminho do dinheiro.
- **Gateways → serviços:** os testes de ponta a ponta do Playwright passam por eles.
- **Eventos do Kafka:** o Pact tem suporte a mensagens, mas os eventos não entraram aqui.

## Alternativas descartadas

- **Spring Cloud Contract:** nasceu para o ecossistema Spring, e dois dos quatro serviços envolvidos são
  Quarkus.
- **Pact Broker:** mais um serviço para hospedar, sem ganho em *monorepo* com um só ramo.
- **Conferir só o OpenAPI:** diz o formato da API, não o que cada consumidor usa, e não permitiria mudar um
  campo que ninguém lê.

## Consequências

- Renomear um campo que alguém lê agora reprova o build do provedor, sem subir o sistema.
- O contrato é do formato, e não da regra: "o razão recusa saldo insuficiente com 422" está no contrato;
  se o razão calcula o saldo certo, não.
- Os arquivos são gerados: um conflito de merge neles se resolve regravando, não à mão.
