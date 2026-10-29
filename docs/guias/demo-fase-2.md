# Guia: demonstração da Fase 2

Roda os quatro serviços na sua máquina e faz Pix de verdade pela API: com chave, com QR Code, para
uma conta encerrada (e o estorno) e com o razão derrubado no meio da transferência. No fim, confere
que o extrato montado pelos eventos bate com o razão. Leva uns 2 minutos.

## 1. Subir a infraestrutura e os serviços

Na raiz do projeto:

```bash
docker compose -f infra/docker/compose.yaml up -d --wait   # PostgreSQL, MongoDB, Redis, Kafka, Keycloak
./mvnw package -DskipTests
./scripts/servicos.sh subir                                # os serviços e os dois gateways
```

O `subir` espera cada serviço responder na verificação de saúde. Se algum não subir, o log está
em `.servicos/<serviço>.log`.

## 2. Rodar a demonstração

```bash
./scripts/demo-fase-2.sh
```

> **Desde a Fase 4, todo serviço exige token** ([ADR-0027](../adr/0027-token-de-servico-e-razao-sem-rota.md)).
> O script já pede um sozinho ao Keycloak. Para os `curl` feitos à mão neste guia, carregue o token
> de serviço antes e mande-o no cabeçalho:
>
> ```bash
> source scripts/lib/token.sh
> curl -H "Authorization: Bearer $(token)" localhost:8081/v1/contas/<id>
> ```

O script ([demo-fase-2.sh](../../scripts/demo-fase-2.sh)) só usa `bash` e `curl`, cria clientes
fictícios com CPFs gerados na hora e confere o código HTTP de cada passo:

| Passo | O que mostra |
|---|---|
| 1 | Três clientes com conta (João, Maria, Joana); o razão recebe cada conta pelo evento `ContaAberta` |
| 2 | Depósito de R$ 1.000,00 para o João |
| 3 | Maria registra as chaves e-mail e CPF; o CPF de outra pessoa é recusado (`422 titularidade-nao-confere`) |
| 4 | "Confira o destinatário": o titular e a chave mascarada |
| 5 | Pix de R$ 150,00 pela chave e-mail → `201`, `CONCLUIDA` |
| 6 | O mesmo Pix reenviado (mesma `Idempotency-Key`) → `200`, a mesma transferência, nada se move de novo |
| 7 | QR Code de R$ 42,50: o João paga pelo copia e cola; o mesmo QR Code com o valor trocado é recusado pelo CRC |
| 8 | Pix para a conta encerrada da Joana: o crédito é recusado e a Saga **estorna** → `ESTORNADA` |
| 9 | **O razão é derrubado** e o João faz um Pix: `202 Accepted`. O razão volta, e o Pix termina sozinho → `CONCLUIDA` |
| 10 | Extrato do João, montado pelos eventos no MongoDB: o saldo bate com o do razão |
| 11 | A Liquidação Pix voltou ao saldo do início (nenhum dinheiro da demonstração parado em trânsito) e o razão íntegro |

Saída esperada (resumida):

```text
▶ 8. A Joana encerra a conta; o João faz um Pix para ela: o crédito é recusado e a Saga estorna
  ...
  ✔ ESTORNADA: os R$ 30,00 voltaram para o João

▶ 9. O razão cai no meio de um Pix — e a Saga termina sozinha quando ele volta
  ✔ razao parado
  ✔ 202 Accepted: o pedido foi aceito, situação CRIADA, e espera o razão
  ...
  ✔ CONCLUIDA depois que o razão voltou, sem ninguém apertar nada

▶ 10. Extrato do João, montado pelos eventos (CQRS no MongoDB)
  ✔ saldo do extrato = saldo do razão: R$ 787.50

▶ 11. Conferência: a Liquidação Pix voltou ao que era e o razão está íntegro
  ✔ nenhum dinheiro desta demonstração parado em trânsito (Liquidação Pix: R$ 0.00)
  ✔ razão íntegro: o balanço fecha
```

A conta do saldo: 1.000,00 − 150,00 − 42,50 − 30,00 + 30,00 (estorno) − 20,00 = **787,50**.

## 3. O que acontece no passo 9

O pix grava a transferência e chama o razão para debitar o João. O razão está fora do ar: **sem
resposta**. O pix não sabe se o dinheiro saiu ou não — e por isso não estorna nem desiste. Responde
`202 Accepted` ao cliente ("em processamento") e tenta o mesmo passo de novo, com a mesma chave de
idempotência, esperando 1 s, 2 s, 4 s... entre as tentativas.

Quando o razão volta, a próxima tentativa passa, a Saga segue para o crédito e termina. Se o razão
tivesse debitado e só a resposta se perdesse, a nova tentativa receberia o lançamento original —
o dinheiro não sai duas vezes. O porquê está na [ADR-0015](../adr/0015-saga-orquestrada-no-pix.md).

## 4. Para ir além

- **Veja a transferência por dentro:** `curl localhost:8083/v1/transferencias/<id>` mostra a
  situação, quantas tentativas, a hora da próxima e os lançamentos de cada passo.
- **Derrube o Redis** (`docker compose -f infra/docker/compose.yaml stop redis`) e repita um Pix:
  continua funcionando, porque a trava contra o segundo clique falha aberta e o banco garante
  sozinho ([ADR-0018](../adr/0018-redis-na-frente-do-banco-no-pix.md)).
- **Apague o extrato e reconstrua** a partir do tópico do Kafka:
  [reconstruir-extrato.sh](../../scripts/reconstruir-extrato.sh) (instruções no
  [README do extrato](../../servicos/extrato/README.md)).
- **Os eventos no Kafka:** suba o Kafka UI com
  `docker compose -f infra/docker/compose.yaml --profile ferramentas up -d` e abra
  http://localhost:18080.

## 5. Desligar

`./scripts/servicos.sh parar` e `docker compose -f infra/docker/compose.yaml down`.
