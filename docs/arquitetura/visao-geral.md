# Visão geral da arquitetura

Este documento usa o **modelo C4** (de Simon Brown): a arquitetura é desenhada em níveis de zoom,
como um mapa — do mundo inteiro até a rua. Aqui estão os dois primeiros níveis:

1. **Contexto** — o sistema como uma caixa só: quem usa e com quem ele conversa.
2. **Contêineres** — o que roda separadamente por dentro (serviços, bancos, filas).

> "Contêiner", no C4, é qualquer coisa que roda separada (um serviço, um banco de dados) —
> não é o mesmo que contêiner Docker, embora aqui cada um rode num.

## Nível 1 — Contexto

```mermaid
flowchart TB
    cliente(["👤 Cliente<br/><small>pessoa com conta no banco</small>"])
    analista(["👤 Analista de fraude<br/><small>revisa alertas</small>"])
    ledgerly["🏦 <b>Ledgerly Bank</b><br/><small>contas, razão contábil, Pix,<br/>antifraude com IA e extrato</small>"]
    spi["Sistema de pagamentos<br/>instantâneos (simulado)<br/><small>papel do SPI/Bacen</small>"]
    llm["Provedor de LLM<br/><small>explica alertas de fraude</small>"]

    cliente -- "consulta saldo, faz Pix" --> ledgerly
    analista -- "aprova ou bloqueia transações" --> ledgerly
    ledgerly -- "liquida Pix entre bancos" --> spi
    ledgerly -- "pede explicação de alerta" --> llm

    classDef sistema fill:#1168bd,stroke:#0b4884,color:#fff
    classDef externo fill:#999,stroke:#6b6b6b,color:#fff
    classDef pessoa fill:#08427b,stroke:#052e56,color:#fff
    class ledgerly sistema
    class spi,llm externo
    class cliente,analista pessoa
```

O **SPI** (Sistema de Pagamentos Instantâneos) é a infraestrutura do Banco Central que liquida o Pix
entre instituições. Aqui ele é **simulado**: o projeto não se conecta a nenhum sistema real.

## Nível 2 — Contêineres

```mermaid
flowchart TB
    subgraph front["Front-end (React)"]
        web["Internet banking<br/><small>React + TypeScript</small>"]
        backoffice["Backoffice de fraude<br/><small>React + TypeScript</small>"]
    end

    gateway["Dois BFFs<br/><small>Spring Cloud Gateway<br/>um por tela, com sessão</small>"]
    keycloak["Keycloak<br/><small>OAuth2 / OIDC<br/>login e tokens</small>"]

    subgraph servicos["Serviços"]
        contas["contas<br/><small>Spring Boot</small>"]
        razao["razao<br/><small>Spring Boot<br/>partidas dobradas</small>"]
        pix["pix<br/><small>Quarkus<br/>Saga</small>"]
        antifraude["antifraude<br/><small>Quarkus + IA</small>"]
        extrato["extrato<br/><small>CQRS - leitura</small>"]
    end

    kafka{{"Kafka<br/><small>eventos</small>"}}

    pgContas[("PostgreSQL<br/>contas")]
    pgRazao[("PostgreSQL<br/>razao")]
    pgPix[("PostgreSQL<br/>pix")]
    redis[("Redis<br/><small>trava rápida<br/>contra pedido repetido</small>")]
    mongo[("MongoDB<br/>extrato")]

    web & backoffice --> gateway
    gateway -- "login (code + PKCE)" --> keycloak
    gateway -- "Bearer (TokenRelay)" --> contas & pix & extrato & antifraude
    pix -- "avaliar risco (síncrono)" --> antifraude
    pix -- "debitar/creditar/estornar (síncrono)" --> razao
    pix -- "titular, titularidade (síncrono)" --> contas
    contas -- "encerrar (síncrono)" --> razao

    contas --- pgContas
    razao --- pgRazao
    pix --- pgPix
    pix --- redis
    antifraude --- redis
    extrato --- mongo

    contas -- "ContaAberta, ContaBloqueada...<br/>(via Outbox)" --> kafka
    razao -- "LancamentoRegistrado<br/>(via Outbox)" --> kafka
    pix -- "PixConcluido / PixEstornado<br/>(via Outbox)" --> kafka
    kafka --> razao
    kafka --> extrato
    kafka --> antifraude
```

O formato de cada evento e de cada API interna está no [contrato dos eventos](eventos.md).

### O que já roda (Fases 2 a 4)

Rodam os serviços **contas**, **razao**, **pix** e **extrato**, o **Keycloak**, os dois **BFFs**
(internet banking na porta 8080 e backoffice na 8090) e as duas telas em React, servidas pelos
próprios BFFs. Só o antifraude (Fase 5) ainda não existe.

Abrir uma conta **não depende mais do razão no ar** ([ADR-0014](../adr/0014-contas-avisa-o-razao-por-evento.md)):
o serviço de contas grava a conta e o evento juntos, e o razão abre a dele quando o evento chega.

```mermaid
sequenceDiagram
    autonumber
    actor C as Cliente da API
    participant CT as contas (8081)
    participant DBC as Postgres contas
    participant K as Kafka
    participant RZ as razao (8082)

    C->>CT: POST /v1/contas {clienteId}
    CT->>DBC: BEGIN · INSERT conta · INSERT outbox (ContaAberta) · COMMIT
    CT-->>C: 201 Created {agencia, numero, situacao}
    CT->>K: publicador do Outbox (a cada 100 ms)
    K->>RZ: ContaAberta
    RZ->>RZ: INSERT conta ... ON CONFLICT DO NOTHING (idempotente)
    Note over K,RZ: Razão fora do ar? O evento espera no Kafka e é aplicado quando ele volta.
```

Uma transferência Pix é uma **Saga** ([ADR-0015](../adr/0015-saga-orquestrada-no-pix.md)): o pedido
mora no pix, o dinheiro no razão, e o dinheiro em trânsito numa conta interna, a Liquidação Pix.

```mermaid
sequenceDiagram
    autonumber
    actor C as Cliente da API
    participant PX as pix (8083)
    participant RZ as razao (8082)
    participant K as Kafka
    participant EX as extrato (8084)

    C->>PX: POST /v1/transferencias + Idempotency-Key
    Note over PX: Redis: segundo clique? 409 na hora (ADR-0018)
    PX->>PX: INSERT transferência CRIADA
    PX->>RZ: POST /v1/pix/envios (pagador → Liquidação Pix)
    PX->>PX: DEBITADA
    PX->>RZ: POST /v1/pix/recebimentos + lancamentoEnvioId (Liquidação Pix → recebedor)
    PX->>PX: CONCLUIDA + PixConcluido no Outbox (mesma transação)
    PX-->>C: 201 Created
    RZ-)K: LancamentoRegistrado (pelo Outbox do razão)
    PX-)K: PixConcluido
    K-)EX: os dois, em qualquer ordem → "Pix enviado para Maria"
    Note over PX,RZ: Crédito recusado → estorno. Razão sem resposta → 202 e o mesmo passo de novo, até responder.
```

O **extrato** é o lado de leitura do CQRS ([ADR-0016](../adr/0016-extrato-em-cqrs-no-mongodb.md)):
não calcula saldo, só guarda o que o razão já calculou, num formato pronto para mostrar. Se o
MongoDB for apagado, ele se reconstrói lendo o tópico `razao.lancamentos.v1` desde o começo.

### Desempenho (Fase 3)

O Caixa e a Liquidação Pix são **contas quentes**: quase todo lançamento passa por uma delas, e a
trava de cada lançamento fazia todos os depósitos e todos os Pix esperarem na mesma linha. Cada
uma virou 16 **sub-contas**, e cada lançamento trava só a do cliente
([ADR-0019](../adr/0019-contas-quentes-em-subcontas.md)). Por isso o crédito e o estorno do Pix
apontam o envio (`lancamentoEnvioId`): o dinheiro sai da mesma sub-conta em que entrou, uma vez só.

Fora dos serviços, três módulos de medição e lote, que não vão para produção:

| Módulo | O que faz |
| --- | --- |
| `desempenho/carga` | Teste de carga com o Gatling contra o razão no ar ([ADR-0021](../adr/0021-teste-de-carga-com-gatling.md)) |
| `desempenho/jmh` | Micro-benchmarks com o JMH ([ADR-0023](../adr/0023-micro-benchmarks-com-jmh.md)) |
| `lotes/conciliacao` | Conciliação Pix × razão em lote, em paralelo com *Structured Concurrency* ([ADR-0022](../adr/0022-conciliacao-em-lote-com-structured-concurrency.md)) |

Hoje a conciliação lê uma base sintética; ligá-la aos bancos do Pix e do razão é implementar a
interface `Fonte` com uma consulta por faixa de ids.

### Segurança e telas (Fase 4)

A pessoa nunca fala com um serviço: fala com o **BFF** da sua tela
([ADR-0025](../adr/0025-bff-um-por-front-end.md)). O login acontece na página do Keycloak, e o
token fica guardado na sessão do BFF — o navegador só tem um cookie.

```mermaid
sequenceDiagram
    autonumber
    actor A as Ana (navegador)
    participant BFF as BFF internet banking (8080)
    participant KC as Keycloak (18081)
    participant CT as contas (8081)

    A->>BFF: GET / (sem sessão) → clica "Entrar"
    BFF-->>A: 302 para o Keycloak (code_challenge do PKCE)
    A->>KC: usuário e senha, na página do Keycloak
    KC-->>A: 302 de volta ao BFF com o code
    BFF->>KC: troca o code (+ code_verifier) pelos tokens
    BFF-->>A: cookie de sessão LEDGERLY_IB (o token fica no BFF)
    A->>BFF: GET /api/v1/contas/{id} + cookie
    BFF->>CT: GET /v1/contas/{id} + Authorization: Bearer (TokenRelay)
    CT->>CT: confere o token (emissor, audiência, papel) e o dono (claim cliente_id)
    CT-->>BFF: 200 — ou 404 se a conta for de outra pessoa (BOLA, ADR-0026)
    BFF-->>A: a resposta
```

- **Todo serviço exige token**, inclusive de outro serviço: contas, pix e extrato se chamam com
  *client credentials* (papel `servico`). O **razão** aceita só `servico` e não tem rota no BFF
  ([ADR-0027](../adr/0027-token-de-servico-e-razao-sem-rota.md)).
- **O extrato não sabe o dono da conta** (ele se monta só dos eventos): pergunta ao contas, com
  token de serviço, e guarda a resposta num cache sem prazo — o dono de uma conta não muda.
- **O backoffice** (papel `analista`) mostra a fila de Pix em análise e devolve um Pix para outra
  conta do mesmo titular ([ADR-0030](../adr/0030-devolucao-de-pix-em-analise.md)).
- **As telas** ([ADR-0029](../adr/0029-react-vite-vitest-playwright.md)) são testadas com Vitest
  e, de ponta a ponta, com o Playwright contra o sistema inteiro no ar.

### O que muda nas próximas fases

- **Fase 5:** entre o débito e o crédito, o pix pergunta ao **antifraude** a nota de risco. Risco
  alto → a transferência para e vai para o analista.

## Princípios

- **Um banco por serviço.** Nenhum serviço lê a tabela de outro (ver
  [01-bancos-por-servico.sql](../../infra/docker/postgres/init/01-bancos-por-servico.sql)).
- **Domínio no centro (arquitetura hexagonal).** As regras de negócio não importam Spring nem
  Quarkus; o framework fica nas bordas (*adapters*). Testes de arquitetura (ArchUnit) garantem.
- **Nada se apaga no razão.** Um lançamento errado é corrigido com um lançamento de estorno, como
  na contabilidade real.
- **Toda operação que move dinheiro é idempotente.**

As decisões e seus motivos estão nas [ADRs](../adr/README.md).
