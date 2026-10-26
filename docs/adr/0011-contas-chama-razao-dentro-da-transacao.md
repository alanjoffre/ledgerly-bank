# ADR-0011: O serviço de contas chama o razão dentro da transação local (provisório)

- **Status:** Aceita — será substituída na Fase 2 (Outbox + eventos)
- **Data:** 2026-10-26

## Contexto

Abrir, bloquear ou encerrar uma conta muda dois serviços: o de **contas** (cadastro e situação) e
o **razão** (que precisa saber da conta para guardar saldo e recusar débitos se ela estiver
bloqueada). Não existe transação de banco entre dois serviços com bancos separados.

## Decisão

O serviço de contas faz a mudança local e chama o razão **por HTTP, dentro da transação local,
antes do COMMIT**:

| O que acontece | Resultado |
|---|---|
| O razão recusa (ex.: encerrar com saldo) | exceção → transação local desfeita → nada mudou nos dois lados |
| O razão está fora do ar ou demora mais que 5 s | `503` + `Retry-After` → nada mudou (provado em teste e ao vivo) |
| O razão aceita e o COMMIT local falha (raro) | o razão fica **adiantado** |

O último caso é a janela aceita: como **todas as chamadas ao razão são idempotentes**
(`PUT /v1/contas/{id}`, `PUT /v1/contas/{id}/situacao`), repetir a operação acerta os dois lados.
Uma conta "órfã" no razão tem saldo zero e não move dinheiro.

## Alternativas consideradas

- **Outbox Pattern + Kafka já na Fase 1** — é a solução certa e entra na Fase 2. Fazer agora
  misturaria dois assuntos grandes numa fase só.
- **Chamar o razão depois do COMMIT** — a janela inverte: a conta existe aqui e não no razão, e
  depósitos nela falhariam. Pior que o razão adiantado.
- **Two-phase commit (XA)** — trava recursos dos dois lados durante a coordenação, depende de
  suporte dos dois bancos e de um coordenador. O mercado abandonou essa abordagem em
  microsserviços.

## Consequências

- **Positivas:** simples, e o caso comum (razão fora do ar) é tratado com segurança.
- **Negativas:** uma requisição de contas fica presa enquanto o razão responde (até 5 s); a janela
  "razão adiantado" existe. As duas coisas somem na Fase 2.
