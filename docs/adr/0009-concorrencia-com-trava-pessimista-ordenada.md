# ADR-0009: Concorrência — trava pessimista ordenada no razão, otimista nas contas

- **Status:** Aceita
- **Data:** 2026-10-26

## Contexto

Dois pedidos que leem o mesmo saldo ao mesmo tempo, antes de qualquer um gravar, podem os dois
achar que há dinheiro: é o **gasto duplo**. Duas gravações simultâneas na mesma linha podem
apagar uma à outra: é a **atualização perdida** (*lost update*).

Medimos o problema de verdade: com a trava removida do código, o
[teste de concorrência](../../servicos/razao/src/test/java/io/github/alanjoffre/ledgerly/razao/integracao/ConcorrenciaIT.java)
mostrou:

| Cenário | Com a trava | Sem a trava |
|---|---|---|
| 1.500 saques de R$ 1,00 numa conta com R$ 1.000,00 | 1.000 aprovados, 500 recusados, saldo 0 | **1.500 aprovados** (R$ 500 de gasto duplo) |
| 400 transferências cruzadas A↔B de R$ 1,00 | A e B continuam com R$ 1.000,00 | **R$ 11,00 sumiram** (atualizações perdidas; o valor varia a cada rodada) |
| 100 cliques no mesmo depósito (mesma chave) | 1 lançamento + 99 respostas repetidas | 1 lançamento, mas **19 erros falsos** "chave já usada para outro pedido" (\*) |

(\*) Correção de 27/10/2026: a primeira versão desta tabela dizia "20 lançamentos". Refeito o
experimento, o `UNIQUE (chave_idempotencia)` do banco segura e sai 1 lançamento só. O defeito é
outro: sem a trava, os cliques idênticos não esperam em fila, não acham o primeiro lançamento (ainda
não confirmado), tentam gravar, e a violação do `UNIQUE` vira `ChaveIdempotenciaReutilizadaException`.
O cliente que repetiu **o mesmo** pedido recebe "chave já usada para outro pedido". O `UNIQUE`
protege o dinheiro; a trava protege a resposta.

## Decisão

**No razão — trava pessimista, sempre na mesma ordem:**

```sql
SELECT ... FROM conta_razao WHERE id IN (:ids) ORDER BY id FOR UPDATE
```

- `FOR UPDATE` trava as linhas até o fim da transação: quem chega depois **espera**.
- `ORDER BY id` faz todas as transações travarem as contas na mesma ordem. Sem isso, "A→B" trava
  A e espera B enquanto "B→A" trava B e espera A — um **deadlock**. Com a ordem fixa, o teste de
  400 transferências cruzadas passa sem nenhum deadlock.
- O banco é a segunda linha de defesa: `CHECK (permite_saldo_negativo OR saldo >= 0)` recusa saldo negativo mesmo que a
  aplicação erre.

**No serviço de contas — trava otimista (`@Version`):**

- cada gravação é `UPDATE ... SET versao = versao + 1 WHERE id = ? AND versao = ?`. Se outra
  transação gravou antes, nenhuma linha muda e a operação **falha** (409 Conflict) em vez de
  sobrescrever. Sem a trava, 10 de 50 bloqueios simultâneos "venceram".

## Por que cada uma onde está

| | Pessimista (razão) | Otimista (contas) |
|---|---|---|
| Quem chega depois | espera a vez | falha e tenta de novo |
| Bom quando | a disputa é **frequente** (a mesma conta recebe muitos débitos) | a disputa é **rara** (bloquear a mesma conta ao mesmo tempo) |
| Custo | transações em fila na mesma linha | o cliente precisa tratar o 409 |

Com disputa frequente, a trava otimista gera uma tempestade de novas tentativas (*retry storm*):
a maioria das transações falha e repete.

## Alternativas consideradas

- **`UPDATE conta SET saldo = saldo - :v WHERE id = :id AND saldo >= :v`** (atualização atômica
  condicional) — rápido e sem trava explícita, mas não cobre o lançamento com várias partidas nem o
  `saldo_apos` de cada uma; será **medido** contra a trava na Fase 3.
- **Isolamento `SERIALIZABLE`** — o PostgreSQL detecta o conflito sozinho, mas aborta transações
  com frequência sob disputa, e a aplicação precisa repetir.
- **Trava distribuída no Redis** — mais uma peça para falhar; o banco já oferece a trava, na mesma
  transação dos dados.

## Consequências

- **Positivas:** comprovadamente sem gasto duplo, sem atualização perdida, sem deadlock.
- **Negativas — conta quente (*hot account*):** todo depósito e saque trava a conta interna
  **Caixa**, que vira um gargalo: as operações nela entram em fila. É um problema conhecido em
  sistemas financeiros. Fica registrado para ser **medido** na Fase 3, e as soluções candidatas
  (dividir o Caixa em subcontas, ou não travar contas internas que podem ficar negativas) serão
  comparadas com números.
