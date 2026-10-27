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
    gateway --> contas & pix & extrato & antifraude
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

### O que já roda (Fase 2)

Rodam os serviços **contas**, **razao**, **pix** e **extrato**. Front-end, gateway, Keycloak
(Fase 4) e antifraude (Fase 5) ainda não existem: hoje a API é chamada direto.

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
    PX->>RZ: POST /v1/pix/recebimentos (Liquidação Pix → recebedor)
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

### O que muda nas próximas fases

- **Fase 4:** o cliente entra pelo **gateway**, que confere o token do Keycloak; as APIs internas
  passam a exigir token de serviço.
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
