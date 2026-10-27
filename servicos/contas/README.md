# Serviço contas

Cadastro de **clientes** e o **ciclo de vida das contas**: abrir, bloquear, desbloquear, encerrar.
O saldo **não** mora aqui — é do [serviço razão](../razao/README.md), que este serviço consulta e
avisa por eventos.

**Stack:** Java 25 · Spring Boot 4 (Web MVC, Data JPA, RestClient, Kafka) · Hibernate · PostgreSQL 18 ·
Flyway · porta `8081`

## Como funciona

### A conta é uma máquina de estados

```mermaid
stateDiagram-v2
    [*] --> ATIVA: abrir
    ATIVA --> BLOQUEADA: bloquear
    BLOQUEADA --> ATIVA: desbloquear
    ATIVA --> ENCERRADA: encerrar (saldo zero)
    ENCERRADA --> [*]
```

Qualquer outra transição é recusada (`422 transicao-invalida`). Conta bloqueada não encerra direto:
o motivo do bloqueio (fraude, ordem judicial) precisa ser resolvido antes.

### Dados pessoais (LGPD)

- O **CPF** é validado pelos dígitos verificadores antes de ir ao banco.
- A API devolve o CPF **mascarado** (`***.ddd.ddd-**`): quem consulta não precisa do número inteiro
  (princípio da minimização).
- O `toString()` do CPF também mascara: ele não vaza inteiro num log por descuido.
- Os testes usam CPFs **gerados por algoritmo**; nenhum dado pessoal real está no repositório.

### Conversa com o razão: evento para avisar, HTTP para perguntar

Desde a Fase 2 ([ADR-0014](../../docs/adr/0014-contas-avisa-o-razao-por-evento.md)):

| Operação | Como o razão fica sabendo | Com o razão fora do ar |
|---|---|---|
| Abrir, bloquear, desbloquear | Evento no Kafka, via **Outbox** ([ADR-0013](../../docs/adr/0013-outbox-para-publicar-eventos.md)) | **Funciona**; o razão recebe quando voltar |
| Encerrar | HTTP, dentro da transação local — só o razão sabe se o saldo é zero | `503` + `Retry-After: 5`; nada muda |
| Consultar | HTTP (o saldo é do razão) | `503` + `Retry-After: 5` |

**Outbox:** a mudança na conta e o evento são gravados na **mesma transação** (tabela `outbox`).
Um publicador, a cada 100 ms, manda os pendentes ao Kafka, espera a confirmação (`acks=all`) e só
então os marca como publicados. Kafka fora do ar: a conta abre normalmente, e o evento espera na
tabela. Publicados há mais de 7 dias são apagados.

**Conta recém-aberta:** entre o COMMIT aqui e o razão consumir o `ContaAberta` (normalmente até
~100 ms, o intervalo do publicador, mais o consumo), o razão ainda não conhece a conta. Nessa janela,
`GET /v1/contas/{id}` mostra saldo `0.00` — e isso é exato: sem o evento, o razão não aceitou nenhum
movimento. Encerrar nessa janela também funciona: o `ContaEncerrada` sai depois do `ContaAberta`
(mesma chave, mesma partição), e o razão abre e encerra a conta nessa ordem.

O cliente HTTP do razão tem tempo-limite de 2 s para conectar e 5 s para responder. Sem isso, um
razão lento prenderia as threads deste serviço.

### Eventos publicados

Tópico `contas.eventos.v1`, chave `contaId`. Formato em [eventos.md](../../docs/arquitetura/eventos.md#contaseventosv1).

| Evento | Quando |
|---|---|
| `ContaAberta` | `POST /v1/contas` |
| `ContaBloqueada` · `ContaDesbloqueada` | `POST` · `DELETE /v1/contas/{id}/bloqueio` |
| `ContaEncerrada` | `POST /v1/contas/{id}/encerramento`, depois que o razão aceitou |

### Concorrência: trava otimista

Duas alterações simultâneas na mesma conta: a segunda a gravar recebe `409 conflito-de-atualizacao`
em vez de sobrescrever a primeira (`@Version` do JPA). Sem a trava, 10 de 50 bloqueios simultâneos
gravariam por cima uns dos outros — ver [ADR-0009](../../docs/adr/0009-concorrencia-com-trava-pessimista-ordenada.md).

## API

| Método e caminho | O que faz | Sucesso |
|---|---|---|
| `POST /v1/clientes` | `{"nome","cpf"}` (CPF com ou sem máscara) | 201 |
| `GET /v1/clientes/{id}` | Dados do cliente (CPF mascarado) | 200 |
| `GET /v1/clientes/{id}/contas` | Contas do cliente | 200 |
| `POST /v1/contas` | `{"clienteId"}` — abre a conta; o razão recebe o `ContaAberta` | 201 |
| `GET /v1/contas/{id}` | Conta com o saldo (vindo do razão; `0.00` se o razão ainda não a conhece) | 200 |
| `GET /v1/contas/{id}/titular` | Titular e situação, sem o saldo (`contaId`, `clienteId`, `nome`, `situacao`): responde mesmo com o razão fora do ar. Usado pelo Pix | 200 |
| `POST /v1/contas/{id}/bloqueio` | Bloqueia | 200 |
| `DELETE /v1/contas/{id}/bloqueio` | Desbloqueia | 200 |
| `POST /v1/contas/{id}/encerramento` | Encerra (o razão exige saldo zero) | 200 |
| `POST /v1/contas/{id}/titularidade` | `{"cpf"}` — o CPF é o do titular? Resposta `{"confere": true\|false}`; o CPF completo nunca sai daqui. Usado pelo Pix | 200 |

### Erros

| `type` (`urn:ledgerly:erro:...`) | HTTP | Quando |
|---|---|---|
| `requisicao-invalida` | 400 | Campo inválido (detalhe em `campos`) |
| `cpf-invalido` · `dado-invalido` | 400 | CPF com dígito errado ou mal formado (também na titularidade); nome curto ou longo demais |
| `cliente-nao-encontrado` · `conta-nao-encontrada` | 404 | Não existe |
| `cpf-ja-cadastrado` | 409 | CPF repetido (inclusive em cadastros simultâneos) |
| `conflito-de-atualizacao` | 409 | Outra operação alterou a conta ao mesmo tempo |
| `transicao-invalida` | 422 | Ex.: desbloquear conta que não está bloqueada |
| `encerramento-com-saldo` | 422 | O razão recusou: saldo diferente de zero |
| `razao-indisponivel` | 503 | O razão não respondeu ao consultar ou encerrar; nada foi alterado |

## Rodar

A infraestrutura local precisa estar de pé (PostgreSQL e Kafka, `docker compose -f
infra/docker/compose.yaml up -d --wait`). O razão só é preciso para consultar saldo e encerrar
([como rodar](../razao/README.md#rodar)). Depois:

```bash
./mvnw -pl servicos/contas -am package -DskipTests
java -jar servicos/contas/target/contas-0.1.0-SNAPSHOT.jar
```

Variáveis: `CONTAS_PORTA`, `CONTAS_DB_URL`, `CONTAS_DB_USUARIO`, `CONTAS_DB_SENHA`, `CONTAS_RAZAO_URL`,
`CONTAS_KAFKA` (padrão `localhost:19092`), `CONTAS_OUTBOX_INTERVALO_MS` (padrão `100`).

## Testes

```bash
./mvnw -pl servicos/contas -am verify     # precisa de Docker (Testcontainers: PostgreSQL e Kafka)
```

| Tipo | Classe | O que prova |
|---|---|---|
| Unidade | `CpfTest` | 200 CPFs gerados aceitos; qualquer dígito trocado é recusado |
| Unidade | `ContaTest`, `NumeroContaEClienteTest`, `EventoDeContaTest` | Máquina de estados, dígito da conta, nome, eventos |
| Unidade | `HttpRazaoTest` | Tradução de cada resposta do razão (servidor simulado): `404 conta-nao-encontrada` = conta desconhecida, outro 404 = erro, tempo esgotado |
| Arquitetura | `ArquiteturaTest` | Domínio sem JPA; entidade JPA não sai do adaptador |
| Integração | `ApiIT` | API de ponta a ponta; titularidade; abrir e bloquear com o razão fora do ar funcionam; encerrar recusado não deixa evento |
| Integração | `OutboxIT` | Transação desfeita → nem conta nem evento; o evento chega ao Kafka com o envelope do contrato; ordem por conta |
| Integração | `KafkaForaDoArIT` | Kafka parado (`docker pause`): abrir responde na hora, o evento espera e sai quando o Kafka volta |
| Integração | `PublicadorDoOutboxIT` | Limpeza: só some o publicado há mais de 7 dias |
| Integração | `ConcorrenciaIT` | Trava otimista: 50 bloqueios simultâneos → 1 gravação e **1 evento**; 20 cadastros do mesmo CPF → 1 cliente |

Nos testes de integração, o razão é um **dublê em memória** (`RazaoFalso`), que dá para derrubar
de propósito; o PostgreSQL e o Kafka são de verdade. O contrato real entre os dois serviços será
testado com Pact na Fase 7.

Cobertura (unidade + integração): **97% das linhas, 91% dos ramos**.
