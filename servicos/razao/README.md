# Serviço razão

O **razão contábil** do Ledgerly Bank: guarda cada movimento de dinheiro como um lançamento em
**partidas dobradas** e mantém o saldo das contas. É o coração do banco e o ponto mais disputado do
sistema — aqui ficam a trava de concorrência e a idempotência.

**Stack:** Java 25 · Spring Boot 4 (Web MVC, JDBC) · PostgreSQL 18 · Flyway · porta `8082`

## Como funciona

Um depósito de R$ 100,00 vira um lançamento com duas partidas:

| Conta | Natureza | Partida | Efeito no saldo |
|---|---|---|---|
| Caixa (interna) | devedora (ativo do banco) | **débito** R$ 100,00 | +100 |
| Maria | credora (o banco deve a ela) | **crédito** R$ 100,00 | +100 |

Débitos = créditos, sempre. A soma das contas devedoras é igual à das credoras — o **balanço fecha**,
e `GET /v1/integridade` confere isso a qualquer momento.

### Organização interna (arquitetura hexagonal)

```mermaid
flowchart LR
    subgraph entrada["Adaptadores de entrada"]
        web["API REST<br/><small>controllers + Problem Details</small>"]
    end
    subgraph nucleo["Núcleo (sem framework de banco/web)"]
        app["Aplicação<br/><small>LancamentoService<br/>ContaRazaoService</small>"]
        dom["Domínio<br/><small>ContaRazao · Lancamento<br/>Escrituracao · Partida</small>"]
        portas["Portas<br/><small>ContaRazaoRepository<br/>LancamentoRepository</small>"]
    end
    subgraph saida["Adaptadores de saída"]
        jdbc["JdbcClient<br/><small>SQL explícito, FOR UPDATE</small>"]
    end
    db[(PostgreSQL)]

    web --> app --> dom
    app --> portas
    jdbc -. implementa .-> portas
    jdbc --> db
```

As setas só apontam para dentro: o domínio não conhece o banco nem o HTTP. O
[teste de arquitetura](src/test/java/io/github/alanjoffre/ledgerly/razao/ArquiteturaTest.java)
reprova o build se alguém inverter uma seta.

### O roteiro de toda operação que move dinheiro

Tudo numa transação só ([LancamentoService](src/main/java/io/github/alanjoffre/ledgerly/razao/aplicacao/LancamentoService.java)):

1. **travar** as contas, em ordem de id (`SELECT ... ORDER BY id FOR UPDATE`) — sem gasto duplo,
   sem deadlock ([ADR-0009](../../docs/adr/0009-concorrencia-com-trava-pessimista-ordenada.md));
2. **conferir a chave de idempotência** — depois de travar, para pedidos iguais entrarem em fila
   ([ADR-0010](../../docs/adr/0010-idempotencia-pela-chave-no-banco.md));
3. **escriturar** no domínio — saldo, situação da conta, partidas dobradas;
4. **gravar** o lançamento e os saldos novos.

### O banco de dados como segunda linha de defesa

Mesmo que a aplicação erre, o PostgreSQL recusa ([migração V1](src/main/resources/db/migration/V1__esquema_do_razao.sql)):

| Regra | Como o banco garante |
|---|---|
| Saldo de cliente nunca negativo | `CHECK (permite_saldo_negativo OR saldo >= 0)` |
| Débitos = créditos em todo lançamento | gatilho `partidas_dobradas`, conferido no COMMIT |
| Lançamento nunca é alterado nem apagado | gatilhos `lancamento_imutavel` e `partida_imutavel` |
| Uma chave de idempotência, um lançamento | `UNIQUE (chave_idempotencia)` |

## API

Todo pedido que move dinheiro exige o cabeçalho `Idempotency-Key`. Valores em dinheiro são texto
(`"10.50"`) — ver [ADR-0012](../../docs/adr/0012-erros-problem-details-e-dinheiro-como-texto.md).

| Método e caminho | O que faz | Sucesso |
|---|---|---|
| `PUT /v1/contas/{id}` | Abre a conta no razão (idempotente) — `{"moeda":"BRL"}` | 201 nova · 200 já existia |
| `GET /v1/contas/{id}` | Saldo e situação | 200 |
| `PUT /v1/contas/{id}/situacao` | `{"situacao":"BLOQUEADA"\|"ATIVA"\|"ENCERRADA"}` | 200 |
| `GET /v1/contas/{id}/extrato?limite=50` | Últimas partidas, com o saldo após cada uma (limite 1–500) | 200 |
| `POST /v1/depositos` | `{"contaId","valor","descricao"?}` | 201 · 200 repetido |
| `POST /v1/saques` | `{"contaId","valor","descricao"?}` | 201 · 200 repetido |
| `POST /v1/transferencias` | `{"origemId","destinoId","valor","descricao"?}` | 201 · 200 repetido |
| `GET /v1/lancamentos/{id}` | Um lançamento com as partidas | 200 |
| `GET /v1/integridade` | Conferência do razão inteiro | 200 (`"integro": true/false`) |
| `GET /actuator/health` | Saúde do serviço | 200 |

Resposta repetida (mesma chave, mesmo pedido): `200 OK` + cabeçalho `Idempotent-Replayed: true` e
o lançamento original.

### Erros

Formato Problem Details (RFC 9457). Decida pelo `type`, nunca pelo texto.

| `type` (`urn:ledgerly:erro:...`) | HTTP | Quando |
|---|---|---|
| `requisicao-invalida` | 400 | Campo inválido (detalhe em `campos`), JSON malformado, cabeçalho faltando, limite fora da faixa |
| `conta-nao-encontrada` | 404 | Conta ou lançamento inexistente |
| `saldo-insuficiente` | 422 | O débito deixaria o saldo negativo |
| `conta-inativa` | 422 | Conta encerrada, ou bloqueada numa saída de dinheiro |
| `encerramento-com-saldo` | 422 | Encerrar conta com saldo diferente de zero |
| `lancamento-invalido` | 422 | Valor zero, moeda diferente, chave de idempotência mal formada |
| `chave-idempotencia-reutilizada` | 422 | A chave já foi usada num pedido diferente |
| `operacao-nao-permitida` | 422 | Movimentar a conta interna Caixa; transferir para a mesma conta |
| `conta-ja-existe` | 422 | Abrir de novo a mesma conta com outra moeda |

## Rodar

```bash
docker compose -f infra/docker/compose.yaml up -d --wait     # na raiz do projeto
./mvnw -pl servicos/razao -am package -DskipTests
java -jar servicos/razao/target/razao-0.1.0-SNAPSHOT.jar
```

Variáveis de ambiente: `RAZAO_PORTA`, `RAZAO_DB_URL`, `RAZAO_DB_USUARIO`, `RAZAO_DB_SENHA`, `RAZAO_DB_POOL`.

## Testes

```bash
./mvnw -pl servicos/razao -am verify     # precisa de Docker (Testcontainers)
```

| Tipo | Classe | O que prova |
|---|---|---|
| Unidade | `ContaRazaoTest`, `LancamentoTest`, `EscrituracaoTest` | Regras do domínio, sem banco (milissegundos) |
| Arquitetura | `ArquiteturaTest` | Domínio sem framework; camadas em ordem |
| Integração | `LancamentoServiceIT` | Casos de uso com PostgreSQL real; integridade conferida após cada teste |
| Integração | `ConcorrenciaIT` | 1.500 saques simultâneos sem gasto duplo; 400 transferências cruzadas sem deadlock; idempotência sob disputa |
| Integração | `BancoDeDadosIT` | O banco recusa sozinho: saldo negativo, lançamento desbalanceado, alteração do razão |
| Integração | `ApiIT` | API de ponta a ponta: códigos HTTP, Problem Details, `Idempotency-Key` |

**Prova reversa:** com a trava `FOR UPDATE` removida, o `ConcorrenciaIT` falha — 1.500 saques
aprovados numa conta de R$ 1.000,00. Números na [ADR-0009](../../docs/adr/0009-concorrencia-com-trava-pessimista-ordenada.md).

Cobertura (unidade + integração): **98% das linhas, 96% dos ramos**.
