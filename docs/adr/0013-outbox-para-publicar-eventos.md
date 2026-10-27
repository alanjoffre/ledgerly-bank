# ADR-0013: Outbox para publicar eventos

- **Status:** Aceita
- **Data:** 2026-10-27

## Contexto

Na Fase 2 os serviços passam a avisar uns aos outros por eventos no Kafka: o razão avisa que
registrou um lançamento, o serviço de contas avisa que abriu ou bloqueou uma conta, o Pix avisa
que concluiu uma transferência.

O jeito ingênuo de publicar é a **escrita dupla** (*dual write*): gravar no banco e, em seguida,
mandar ao Kafka. São dois sistemas sem transação entre eles, e a falha no meio deixa os dois em
desacordo:

| O que falha | Resultado |
|---|---|
| O serviço cai depois do COMMIT e antes de publicar | O dado mudou e **o evento se perdeu**: o extrato nunca mostra o lançamento |
| Publica antes do COMMIT, e o COMMIT falha | **Evento de um fato que não aconteceu**: o extrato mostra dinheiro que não se moveu |
| O Kafka está fora do ar | Ou a operação falha por causa do Kafka, ou o evento se perde |

## Decisão

**Outbox Pattern** (*Transactional Outbox*): o evento é gravado numa tabela `outbox` do próprio
serviço, **na mesma transação** do dado que mudou. Um publicador separado lê a tabela e manda ao
Kafka.

```sql
CREATE TABLE outbox (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,  -- ordem de gravação
    evento_id    UUID         NOT NULL UNIQUE,  -- o "eventoId" do envelope
    topico       VARCHAR(100) NOT NULL,
    chave        VARCHAR(100) NOT NULL,          -- a key do Kafka (id do agregado)
    tipo         VARCHAR(60)  NOT NULL,          -- vai também no cabeçalho "tipo"
    payload      JSONB        NOT NULL,          -- o envelope inteiro, pronto para mandar
    criado_em    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    publicado_em TIMESTAMPTZ                     -- NULL = ainda não publicado
);
CREATE INDEX outbox_pendentes ON outbox (id) WHERE publicado_em IS NULL;
```

O publicador, a cada 100 ms, numa transação:

1. `SELECT ... WHERE publicado_em IS NULL ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED` — pega um
   lote dos pendentes, na ordem em que foram gravados;
2. manda cada um ao Kafka (produtor idempotente, `acks=all`) e **espera a confirmação** de todos;
3. `UPDATE outbox SET publicado_em = now() WHERE id IN (...)` e COMMIT.

Se o Kafka falhar no passo 2, a transação é desfeita e o lote volta na próxima rodada. Linhas
publicadas há mais de 7 dias são apagadas pelo próprio publicador.

O formato do envelope, os tópicos e as chaves estão em [eventos.md](../arquitetura/eventos.md).

## A garantia que se ganha e a que não se ganha

- **Ganha:** nenhum evento se perde, e nenhum evento sai de um fato desfeito. O dado e o evento
  entram juntos ou não entram.
- **Não ganha "exatamente uma vez":** se o publicador cair depois de o Kafka confirmar e antes do
  COMMIT do passo 3, o lote é publicado de novo. A entrega é **pelo menos uma vez**
  (*at-least-once*), e por isso **todo consumidor é idempotente**: o mesmo evento duas vezes dá o
  mesmo resultado que uma.
- **Ordem:** com uma instância do publicador, os eventos saem na ordem do `id`, e eventos da mesma
  chave chegam em ordem (mesma partição). O `SKIP LOCKED` deixa duas instâncias rodarem sem
  publicar a mesma linha, mas aí dois lotes podem sair fora de ordem entre si — com várias réplicas
  (Fase 6) será preciso um publicador só (eleição de líder) ou os consumidores tolerarem a ordem
  trocada, como o extrato já tolera.

## Alternativas consideradas

- **CDC com Debezium** (*Change Data Capture*): lê o log de transações do PostgreSQL (WAL) e manda
  ao Kafka, sem consulta periódica e com latência menor. É o que se usa em volume alto. Fica de fora
  porque exige o Kafka Connect, mais uma peça para subir e operar, e a consulta a cada 100 ms dá
  conta do volume deste projeto. A tabela `outbox` é a mesma nas duas soluções: trocar o publicador
  por Debezium depois não muda o resto do código.
- **Escrita dupla com nova tentativa** — não resolve "o serviço caiu no meio": a tentativa morre
  junto.
- **Transação distribuída (XA / two-phase commit) entre banco e Kafka** — o Kafka não participa de
  XA, e o mercado abandonou o 2PC em microsserviços (ver [ADR-0011](0011-contas-chama-razao-dentro-da-transacao.md)).
- **Publicar só no Kafka e o banco consumir de lá** (*event sourcing* puro) — muda o modelo do razão
  inteiro; o razão continua sendo a fonte da verdade em tabela.

## Consequências

- **Positivas:** o caminho crítico não depende do Kafka: com o Kafka fora do ar, depósitos e
  transferências continuam funcionando, e os eventos esperam na tabela até ele voltar.
- **Negativas:**
  - **latência**: o evento sai até ~100 ms depois do COMMIT (o intervalo da consulta);
  - **mais escrita no banco**: cada operação grava uma linha a mais e depois a atualiza;
  - **consumidores precisam ser idempotentes**, sempre;
  - cada serviço tem o seu publicador, um pouco de código repetido entre Spring e Quarkus — de
    propósito, para cada serviço não depender de uma biblioteca de outro framework.
