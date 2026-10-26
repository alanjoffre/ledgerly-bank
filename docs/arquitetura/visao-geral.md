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

    gateway["API Gateway<br/><small>Spring Cloud Gateway<br/>+ Keycloak (OAuth2/OIDC)</small>"]

    subgraph servicos["Serviços"]
        contas["contas<br/><small>Spring Boot</small>"]
        razao["razao<br/><small>Spring Boot<br/>partidas dobradas</small>"]
        pix["pix<br/><small>Quarkus nativo<br/>Saga</small>"]
        antifraude["antifraude<br/><small>Quarkus + IA</small>"]
        extrato["extrato<br/><small>CQRS - leitura</small>"]
    end

    kafka{{"Kafka<br/><small>eventos</small>"}}

    pgContas[("PostgreSQL<br/>contas")]
    pgRazao[("PostgreSQL<br/>razao")]
    pgPix[("PostgreSQL<br/>pix")]
    redis[("Redis<br/><small>idempotência,<br/>velocidade</small>")]
    mongo[("MongoDB<br/>extrato")]

    web & backoffice --> gateway
    gateway --> contas & pix & extrato & antifraude
    pix -- "avaliar risco (síncrono)" --> antifraude
    pix -- "debitar/creditar (síncrono)" --> razao

    contas --- pgContas
    razao --- pgRazao
    pix --- pgPix
    pix --- redis
    antifraude --- redis
    extrato --- mongo

    razao -- "LancamentoRegistrado<br/>(via Outbox)" --> kafka
    pix -- "PixConcluido / PixEstornado" --> kafka
    kafka --> extrato
    kafka --> antifraude
```

### O que já roda (Fase 1)

Hoje rodam os serviços **contas** e **razao**. Abrir uma conta atravessa os dois:

```mermaid
sequenceDiagram
    autonumber
    actor C as Cliente da API
    participant CT as contas (8081)
    participant DBC as Postgres contas
    participant RZ as razao (8082)
    participant DBR as Postgres razao

    C->>CT: POST /v1/contas {clienteId}
    CT->>DBC: BEGIN · INSERT conta (número da sequência)
    CT->>RZ: PUT /v1/contas/{id} {moeda: BRL} (idempotente, tempo-limite 5 s)
    RZ->>DBR: INSERT ... ON CONFLICT DO NOTHING
    RZ-->>CT: 201 Created
    CT->>DBC: COMMIT
    CT-->>C: 201 Created {agencia, numero, situacao}
    Note over CT,RZ: Se o razão falhar ou demorar: ROLLBACK aqui e 503 ao cliente (ADR-0011)
```

E uma transferência, dentro do razão, numa transação só:

```mermaid
sequenceDiagram
    autonumber
    actor C as Cliente da API
    participant RZ as razao
    participant DB as Postgres razao

    C->>RZ: POST /v1/transferencias + Idempotency-Key
    RZ->>DB: BEGIN
    RZ->>DB: SELECT contas ORDER BY id FOR UPDATE (trava em ordem)
    RZ->>DB: SELECT lançamento pela chave (já existe? devolve o original)
    Note over RZ: Domínio: saldo, situação, partidas dobradas
    RZ->>DB: INSERT lançamento + partidas · UPDATE saldos
    RZ->>DB: COMMIT (gatilho confere débitos = créditos)
    RZ-->>C: 201 Created + Location
```

### Como uma transferência Pix atravessará o sistema (visão da Fase 2)

1. O cliente pede um Pix no internet banking; o **gateway** confere o token de acesso.
2. O serviço **pix** registra o pedido com uma **chave de idempotência** (no Redis): se o mesmo
   pedido chegar duas vezes (o cliente clicou duas vezes, a rede repetiu), ele é processado uma só.
3. O **antifraude** devolve uma nota de risco em milissegundos. Risco alto → o Pix para e vai para
   o analista.
4. O **razão** debita a conta de origem e credita a de destino numa única transação de banco, com
   **partidas dobradas** — e grava, na mesma transação, o evento numa tabela de **Outbox**.
5. Um publicador lê o Outbox e manda o evento ao **Kafka**. O **extrato** consome e atualiza o
   MongoDB; o cliente vê a transação na tela.
6. Se um passo falhar depois do débito, a **Saga** executa a compensação (o estorno).

## Princípios

- **Um banco por serviço.** Nenhum serviço lê a tabela de outro (ver
  [01-bancos-por-servico.sql](../../infra/docker/postgres/init/01-bancos-por-servico.sql)).
- **Domínio no centro (arquitetura hexagonal).** As regras de negócio não importam Spring nem
  Quarkus; o framework fica nas bordas (*adapters*). Testes de arquitetura (ArchUnit) garantem.
- **Nada se apaga no razão.** Um lançamento errado é corrigido com um lançamento de estorno, como
  na contabilidade real.
- **Toda operação que move dinheiro é idempotente.**

As decisões e seus motivos estão nas [ADRs](../adr/README.md).
