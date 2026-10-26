# Guia: demonstração da Fase 1

Roda o banco de verdade na sua máquina e percorre o fluxo inteiro pela API, como um cliente faria.
Leva uns 3 minutos.

## 1. Subir a infraestrutura e os dois serviços

Na raiz do projeto, em três terminais:

```bash
# Terminal 1 — bancos de dados e Kafka
docker compose -f infra/docker/compose.yaml up -d --wait
./mvnw package -DskipTests
java -jar servicos/razao/target/razao-0.1.0-SNAPSHOT.jar
```

```bash
# Terminal 2
java -jar servicos/contas/target/contas-0.1.0-SNAPSHOT.jar
```

Espere a linha `Started ...Aplicacao` nos dois.

## 2. Rodar a demonstração

```bash
# Terminal 3
./scripts/demo-fase-1.sh
```

O script ([demo-fase-1.sh](../../scripts/demo-fase-1.sh)) só usa `bash` e `curl`, gera CPFs
fictícios na hora e confere o código HTTP de cada passo:

| Passo | O que mostra |
|---|---|
| 1–2 | Cadastro com CPF mascarado na resposta; conta aberta nos dois serviços |
| 3–4 | Depósito, e o **mesmo depósito reenviado**: `200` com o lançamento original, nada movido de novo |
| 5–6 | Transferência; saque maior que o saldo → `422 saldo-insuficiente` |
| 7 | Saldo consultado pelo serviço de contas, que pergunta ao razão |
| 8 | Conta bloqueada: o saque é recusado, mas a transferência para ela entra |
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

Pare o razão (Ctrl+C no terminal 1) e tente abrir uma conta:

```bash
curl -i -X POST localhost:8081/v1/contas -H 'Content-Type: application/json' \
     -d '{"clienteId":"<id de um cliente do passo 1>"}'
```

Resposta: `503` com `Retry-After: 5` — e nenhuma conta fica gravada pela metade
([ADR-0011](../adr/0011-contas-chama-razao-dentro-da-transacao.md)).

## 4. Desligar

Ctrl+C nos serviços e `docker compose -f infra/docker/compose.yaml down`.
