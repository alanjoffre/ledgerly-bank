# ADR-0018: Redis como trava rápida na frente do banco, no Pix

- **Status:** Aceita
- **Data:** 2026-10-27

## Contexto

O cliente aperta "Pagar" duas vezes, ou o app reenvia porque a rede oscilou. Os dois pedidos têm a
mesma `Idempotency-Key` e chegam ao mesmo tempo. A [ADR-0010](0010-idempotencia-pela-chave-no-banco.md)
decidiu que a chave de idempotência mora no banco, na mesma transação do dado — e registrou que o
Redis entraria no Pix como camada rápida **antes** do banco, não no lugar dele.

No Pix, o segundo pedido não deveria nem começar: o primeiro ainda está no meio da Saga (chamando o
razão), e um segundo pedido igual só disputaria a mesma linha.

## Decisão

Duas camadas, cada uma com um papel:

| Camada | Comando | Papel |
|---|---|---|
| **Redis** (rápida) | `SET pix:pedido:<chave> 1 NX PX 30000` | O segundo clique, enquanto o primeiro corre, recebe `409` + `Retry-After: 1` na hora, sem tocar no banco |
| **PostgreSQL** (a garantia) | `UNIQUE (chave_idempotencia)` + `INSERT ... ON CONFLICT DO NOTHING` | Uma chave, uma transferência — com ou sem Redis |

- `NX`: grava só se não existir. `PX`: com validade. Num comando só, atômico.
- A trava é liberada quando o pedido termina; depois disso, o mesmo pedido de novo vai ao banco e
  recebe `200` + `Idempotent-Replayed: true` com a transferência original.
- **Falha aberta** (*fail-open*): se o Redis não responder em 1 s, a trava deixa passar e o banco
  segura sozinho. O Redis fora do ar deixa o Pix um pouco mais lento sob cliques repetidos, nunca
  errado nem parado.

## Números (testes de integração)

| Cenário | Resultado |
|---|---|
| 50 cliques simultâneos, Redis no ar | 1 × `201`, o resto `409` ou `200`; 1 transferência, 1 débito |
| 20 cliques simultâneos, Redis **fora do ar** | todos `201` ou `200`; 1 transferência, 1 débito — o `UNIQUE` decidiu |

## Alternativas consideradas

- **Só o banco** — correto, e é o que vale quando o Redis cai. A trava rápida existe para o
  segundo pedido não chegar a disputar a linha nem a esperar a Saga do primeiro.
- **Só o Redis** — rápido, mas é outra base: a transferência pode ser gravada e a chave não (ou o
  contrário). A garantia precisa estar na mesma transação do dado.
- **Falha fechada** (Redis fora do ar = recusar o Pix) — um componente de velocidade viraria um
  ponto único de falha do pagamento.

## Consequências

- **Positivas:** o segundo clique é barato; o Redis pode cair sem consequência para o dinheiro.
- **Negativas:** mais uma peça no caminho; e uma trava que fica presa (o pedido morreu sem
  liberar) segura pedidos iguais por até 30 s — o que é aceitável, porque são pedidos repetidos.
