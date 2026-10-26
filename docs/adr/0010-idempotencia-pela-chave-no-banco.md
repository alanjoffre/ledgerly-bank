# ADR-0010: Idempotência pela chave no banco de dados

- **Status:** Aceita
- **Data:** 2026-10-26

## Contexto

Na internet, pedidos se repetem: o cliente clica duas vezes, o app perde a resposta e reenvia, um
balanceador repete a chamada. Para operações que movem dinheiro, "processar duas vezes" é
inaceitável.

## Decisão

- Toda operação que move dinheiro exige o cabeçalho **`Idempotency-Key`** (padrão em discussão no
  IETF e usado por Stripe e outros), com até 100 caracteres visíveis — um UUID é o uso típico.
- A chave é gravada no lançamento, com restrição **`UNIQUE`** no banco.
- A conferência acontece **depois de travar as contas**: dois pedidos iguais simultâneos travam as
  mesmas contas, entram em fila, e o segundo já enxerga o lançamento do primeiro.
- Resposta:
  - mesma chave e **mesmo pedido** → `200 OK`, cabeçalho `Idempotent-Replayed: true` e o
    lançamento original, byte a byte;
  - mesma chave e **pedido diferente** → `422` `chave-idempotencia-reutilizada`;
  - corrida entre pedidos diferentes com a mesma chave (contas diferentes, sem fila em comum) →
    a restrição `UNIQUE` barra; o teste prova que só 1 de 20 passa.
- Pedido **recusado** (saldo insuficiente, conta bloqueada) **não consome a chave**: nada foi
  gravado, então repetir depois é uma tentativa nova.

## Alternativas consideradas

- **Chave no Redis** (com expiração) — rápido, mas em outra base: o lançamento pode ser gravado e a
  chave não (ou o contrário). No banco, os dois entram na mesma transação. O Redis entra na Fase 2
  no serviço de Pix, como camada rápida **antes** do banco, não no lugar dele.
- **Gravar também as respostas de erro** — é o que a Stripe faz. Aqui, como o erro não grava
  nada, repetir o pedido é seguro e mais simples.

## Consequências

- **Positivas:** comprovado sob disputa (100 cliques simultâneos → 1 lançamento).
- **Negativas:** a chave fica para sempre na tabela de lançamentos (sem expiração). Para o volume
  deste projeto é irrelevante; num banco real, chaves antigas iriam para uma tabela de histórico.
