# Guia: demonstração da Fase 1

Roda o banco de verdade na sua máquina e percorre o fluxo inteiro pela API, como um cliente faria.
Leva uns 3 minutos.

## 1. Subir a infraestrutura e os serviços

Na raiz do projeto:

```bash
docker compose -f infra/docker/compose.yaml up -d --wait   # bancos de dados, Kafka e Keycloak
./mvnw package -DskipTests
./scripts/servicos.sh subir razao
./scripts/servicos.sh subir contas
```

Desde a Fase 2, o serviço de contas avisa o razão por evento: o Kafka precisa estar no ar. Desde a Fase 4,
o Keycloak também: é ele quem dá o token que os dois serviços exigem.

## 2. Rodar a demonstração

```bash
./scripts/demo-fase-1.sh
```

> **Desde a Fase 4, todo serviço exige token** ([ADR-0027](../adr/0027-token-de-servico-e-razao-sem-rota.md)).
> O script já pede um sozinho ao Keycloak. Para os `curl` feitos à mão neste guia, carregue o token
> de serviço antes e mande-o no cabeçalho:
>
> ```bash
> source scripts/lib/token.sh
> curl -H "Authorization: Bearer $(token)" localhost:8081/v1/contas/<id>
> ```

O script ([demo-fase-1.sh](../../scripts/demo-fase-1.sh)) só usa `bash` e `curl`, gera CPFs
fictícios na hora e confere o código HTTP de cada passo:

| Passo | O que mostra |
|---|---|
| 1–2 | Cadastro com CPF mascarado na resposta; conta aberta nos dois serviços (no razão, pelo evento `ContaAberta`) |
| 3–4 | Depósito, e o **mesmo depósito reenviado**: `200` com o lançamento original, nada movido de novo |
| 5–6 | Transferência; saque maior que o saldo → `422 saldo-insuficiente` |
| 7 | Saldo consultado pelo serviço de contas, que pergunta ao razão |
| 8 | Conta bloqueada (o razão recebe pelo evento): o saque é recusado, mas a transferência para ela entra |
| 9 | Encerrar com saldo → recusado pelo razão |
| 10–11 | Extrato com saldo após cada lançamento; conferência do razão: `"integro": true` |

Saída esperada (resumida):

```text
▶ 4. O app reenvia o MESMO depósito (mesma Idempotency-Key): nada é movido de novo
  HTTP 200  {"id":"10c2e0fc-...","tipo":"DEPOSITO","valor":"1000.00",...}
  ✔ 200 OK com o lançamento original — o saldo continua R$ 1.000,00
...
▶ 11. Conferência do razão inteiro: balanço fecha, saldos batem com os lançamentos
  HTTP 200  {"integro":true,"balancos":[{"moeda":"BRL","devedoras":"1000.00","credoras":"1000.00","fecha":true}],...}
  ✔ razão íntegro
```

## 3. Bônus: derrube o razão

```bash
./scripts/servicos.sh parar razao
curl -i -X POST localhost:8081/v1/contas -H 'Content-Type: application/json' \
     -d '{"clienteId":"<id de um cliente do passo 1>"}'
```

Resposta: `201 Created`. A conta está gravada no serviço de contas, e o evento `ContaAberta` espera
no Kafka. Suba o razão de novo (`./scripts/servicos.sh subir razao`) e, logo
depois de ele ficar pronto, a conta existe nele também.

Na Fase 1 a resposta era `503`: o serviço de contas chamava o razão dentro da própria transação, e
sem razão não abria conta ([ADR-0011](../adr/0011-contas-chama-razao-dentro-da-transacao.md)). A
[ADR-0014](../adr/0014-contas-avisa-o-razao-por-evento.md) trocou por evento. Só o **encerramento**
continua síncrono, porque precisa perguntar ao razão se o saldo é zero: com o razão fora do ar,
encerrar responde `503` com `Retry-After: 5`.

## 4. Desligar

`./scripts/servicos.sh parar` e `docker compose -f infra/docker/compose.yaml down`.
