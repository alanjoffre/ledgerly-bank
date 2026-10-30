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
    llm["Ollama<br/><small>modelos de linguagem abertos,<br/>na própria máquina</small>"]

    cliente -- "consulta saldo, faz Pix,<br/>pergunta ao assistente" --> ledgerly
    analista -- "libera ou recusa Pix retido,<br/>devolve, bloqueia" --> ledgerly
    ledgerly -- "liquida Pix entre bancos" --> spi
    ledgerly -- "explicação do alerta,<br/>respostas do assistente" --> llm

    classDef sistema fill:#1168bd,stroke:#0b4884,color:#fff
    classDef externo fill:#999,stroke:#6b6b6b,color:#fff
    classDef pessoa fill:#08427b,stroke:#052e56,color:#fff
    class ledgerly sistema
    class spi,llm externo
    class cliente,analista pessoa
```

O **SPI** (Sistema de Pagamentos Instantâneos) é a infraestrutura do Banco Central que liquida o Pix
entre instituições. Aqui ele é **simulado**: o projeto não se conecta a nenhum sistema real.

O **Ollama** roda os modelos de linguagem na própria máquina, em contêiner: nenhuma API paga, e
nenhum dado sai dali ([ADR-0034](../adr/0034-modelo-de-linguagem-local-ollama.md)). Sem ele, o
banco funciona igual, com textos fixos de reserva.

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
        antifraude["antifraude<br/><small>Quarkus<br/>regras + modelo ONNX</small>"]
        extrato["extrato<br/><small>CQRS - leitura</small>"]
        assistente["assistente<br/><small>Spring Boot + LangChain4j<br/>RAG e ferramentas</small>"]
    end

    ollama["Ollama<br/><small>gemma3 · qwen3-instruct · bge-m3</small>"]

    kafka{{"Kafka<br/><small>eventos</small>"}}

    pgContas[("PostgreSQL<br/>contas")]
    pgRazao[("PostgreSQL<br/>razao")]
    pgPix[("PostgreSQL<br/>pix")]
    pgAntifraude[("PostgreSQL<br/>antifraude")]
    pgvector[("pgvector<br/><small>trechos da ajuda</small>")]
    redis[("Redis<br/><small>trava rápida<br/>contra pedido repetido</small>")]
    mongo[("MongoDB<br/>extrato")]

    web & backoffice --> gateway
    gateway -- "login (code + PKCE)" --> keycloak
    gateway -- "Bearer (TokenRelay)" --> contas & pix & extrato & antifraude & assistente
    pix -- "avaliar risco, antes do débito (síncrono)" --> antifraude
    assistente -- "saldo e lançamentos,<br/>com o token do cliente" --> contas & extrato
    antifraude -- "explicação para a analista" --> ollama
    assistente -- "resposta e vetores" --> ollama
    pix -- "debitar/creditar/estornar (síncrono)" --> razao
    pix -- "titular, titularidade (síncrono)" --> contas
    contas -- "encerrar (síncrono)" --> razao

    contas --- pgContas
    razao --- pgRazao
    pix --- pgPix
    pix --- redis
    antifraude --- pgAntifraude
    extrato --- mongo
    assistente --- pgvector

    contas -- "ContaAberta, ContaBloqueada...<br/>(via Outbox)" --> kafka
    razao -- "LancamentoRegistrado<br/>(via Outbox)" --> kafka
    pix -- "PixConcluido / PixEstornado<br/>(via Outbox)" --> kafka
    kafka --> razao
    kafka --> extrato
```

O formato de cada evento e de cada API interna está no [contrato dos eventos](eventos.md).

### O que já roda (Fases 2 a 5)

Rodam os seis serviços (**contas**, **razao**, **pix**, **antifraude**, **extrato** e
**assistente**), o **Keycloak**, os dois **BFFs** (internet banking na porta 8080 e backoffice na
8090) e as duas telas em React, servidas pelos próprios BFFs. O **Ollama** sobe à parte (perfil `ia`
do compose), porque pede placa de vídeo.

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
    PX->>PX: antifraude (ADR-0033): APROVADA, ou RETIDA / REJEITADA sem mover dinheiro
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

### IA aplicada (Fase 5)

Três usos, cada um com a sua defesa contra o erro do modelo:

- **A nota de risco do Pix** ([ADR-0032](../adr/0032-antifraude-regras-e-modelo-onnx.md)):
  regras explícitas + um modelo de regressão logística treinado em Python (dados sintéticos) e
  executado na JVM em ONNX, em microssegundos. Ela é dada **antes do débito**: nota a partir de 200
  segura o Pix para a analista; a partir de 900, recusa. Sem resposta do antifraude, até R$ 500,00
  o Pix segue e acima disso fica retido ([ADR-0033](../adr/0033-pix-retido-antes-do-debito.md)). O
  cliente não vê a nota nem os motivos.
- **A explicação para a analista** ([ADR-0035](../adr/0035-explicacao-com-trava-de-numeros.md)):
  o `gemma3:4b` escreve um parágrafo com os fatos do Pix retido; uma trava descarta o texto que traz
  um número que não está na avaliação, e vale um texto fixo.
- **O assistente do internet banking** ([ADR-0036](../adr/0036-assistente-rag-ferramentas-e-travas.md)):
  o `qwen3:4b-instruct-2507` responde com a busca nos artigos de ajuda (RAG no pgvector) e três ferramentas **só
  de leitura**, que chamam o contas e o extrato com o token do próprio cliente. Uma trava de entrada
  barra a tentativa de trocar as instruções, e uma de saída barra o valor que não veio das
  ferramentas. A qualidade do modelo de verdade se mede com uma avaliação de 30 perguntas, fora do
  CI ([ADR-0037](../adr/0037-avaliacao-do-assistente-fora-do-ci.md)).

### O que muda nas próximas fases

- **Fase 6:** imagens Docker e Kubernetes, Terraform da AWS validado no CI, observabilidade
  (OpenTelemetry, Prometheus, Grafana) e resiliência (*circuit breaker*, *retry*).

## Princípios

- **Um banco por serviço.** Nenhum serviço lê a tabela de outro (ver
  [01-bancos-por-servico.sql](../../infra/docker/postgres/init/01-bancos-por-servico.sql)).
- **Domínio no centro (arquitetura hexagonal).** As regras de negócio não importam Spring nem
  Quarkus; o framework fica nas bordas (*adapters*). Testes de arquitetura (ArchUnit) garantem.
- **Nada se apaga no razão.** Um lançamento errado é corrigido com um lançamento de estorno, como
  na contabilidade real.
- **Toda operação que move dinheiro é idempotente.**

As decisões e seus motivos estão nas [ADRs](../adr/README.md).
