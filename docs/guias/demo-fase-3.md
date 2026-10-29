# Guia: demonstração da Fase 3

Mede, na sua máquina, o que a divisão das contas quentes em sub-contas mudou (ADR-0019): roda a
mesma carga de depósitos com o Caixa numa linha só e com o Caixa em 16 sub-contas, e mostra a
diferença. Depois, roda a conciliação em lote de 2 milhões de lançamentos, em sequência e em
paralelo (ADR-0022). Leva uns 3 minutos.

## 1. Subir a infraestrutura e gerar os pacotes

Na raiz do projeto:

```bash
docker compose -f infra/docker/compose.yaml up -d --wait   # PostgreSQL, MongoDB, Redis, Kafka, Keycloak
./mvnw package -DskipTests
```

O Keycloak entra desde a Fase 4: a carga pede um token de serviço a ele antes de começar
([ADR-0027](../adr/0027-token-de-servico-e-razao-sem-rota.md)). Não precisa subir os serviços: o
script sobe e derruba o razão sozinho, duas vezes, com uma configuração diferente em cada (a
variável `RAZAO_SUBCONTAS`).

## 2. Rodar a demonstração

```bash
./scripts/demo-fase-3.sh        # 15 s de carga em cada medição
./scripts/demo-fase-3.sh 30     # 30 s, como nas medições da ADR-0019
```

O script ([demo-fase-3.sh](../../scripts/demo-fase-3.sh)) usa o teste de carga do Gatling
(ADR-0021): 64 clientes simultâneos fazendo depósitos, cada um numa conta fictícia diferente.

| Passo | O que mostra |
| --- | --- |
| 1 | `RAZAO_SUBCONTAS=1`: o Caixa é uma linha só, e todo depósito trava essa linha. A vazão fica presa |
| 2 | `RAZAO_SUBCONTAS=16`: cada depósito trava só a sub-conta do Caixa do seu cliente. A mesma carga, várias vezes mais rápida, e o p99 (o cliente mais azarado) cai |
| 3 | `GET /v1/integridade`: o saldo do Caixa e da Liquidação Pix é a soma das sub-contas |
| 4 | A conciliação em lote: as duas fontes (Pix e razão) cruzadas em sequência e em paralelo, com o mesmo resultado |

Saída esperada (os números mudam de máquina para máquina; a proporção entre o passo 1 e o 2, não):

```text
▶ 1. Depósitos com o Caixa numa linha só (RAZAO_SUBCONTAS=1): todos fazem fila na mesma trava
  ✔ 99.12 depósitos por segundo · p99 1043 ms · razão íntegro

▶ 2. Depósitos com o Caixa em 16 sub-contas: cada depósito trava só a do cliente
  ✔ 489.33 depósitos por segundo · p99 288 ms · razão íntegro
  ✔ ganho: 4.9× (mesmo código, mesma máquina, minutos de diferença)
...
▶ 4. Conciliação em lote: 2 milhões de lançamentos Pix × razão, em sequência e em paralelo
...
mesmo resultado: sim
```

Se o razão não ficar íntegro depois de uma carga, o script para e aponta o arquivo com o resumo.

## 3. Ver os gráficos

Cada medição deixa um relatório HTML do Gatling, com a vazão e a latência segundo a segundo:

```bash
ls -td desempenho/carga/target/gatling/*/ | head -2    # os dois relatórios desta demonstração
```

Abra o `index.html` de cada um no navegador. O resumo em texto de cada medição fica em
`desempenho/resultados/carga/demo-*.txt`.

## 4. Medir mais

| O quê | Comando | Onde fica o resultado |
| --- | --- | --- |
| Um cenário de carga | `desempenho/carga/medir.sh <depositos\|pix-envios\|transferencias> <rótulo>` | `desempenho/resultados/carga/` |
| A conciliação, com 10 milhões | `lotes/conciliacao/medir.sh 10000000 16` | `desempenho/resultados/conciliacao/` |
| Os micro-benchmarks (JMH) | `java -jar desempenho/jmh/target/benchmarks.jar` | na tela (ver ADR-0023) |

Só compare medições feitas na mesma sessão: a mesma máquina, com outros programas abertos, varia
uns 20% de um dia para o outro (ADR-0021).

## 5. Desligar

```bash
./scripts/servicos.sh parar
docker compose -f infra/docker/compose.yaml down
```
