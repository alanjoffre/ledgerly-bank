# Integridade e conciliação

Duas conferências diferentes, e nenhuma depende da outra.

## Integridade: o razão bate com ele mesmo

```bash
curl -s -H "Authorization: Bearer $(token)" localhost:8082/v1/integridade
```

| Campo | Deve dar |
| --- | --- |
| `integro` | `true` |
| `balancos[].fecha` | `true`: em cada moeda, devedoras = credoras |
| `contasComSaldoDivergente` | vazio: a soma dos lançamentos é o saldo gravado |
| `lancamentosDesbalanceados` | vazio: em todo lançamento, débitos = créditos |

**Se der `false`, pare e investigue: é o pior defeito que este sistema pode ter.** Não existe
conserto automático, nem deve existir. O razão só cresce: não se apaga nem se edita lançamento, e a
correção é um lançamento novo, com motivo. Anote os ids devolvidos e confira o histórico da conta.

## Conciliação: o Pix e o razão contam a mesma história

**O que existe hoje:** o conciliador em `lotes/conciliacao/` roda só sobre uma fonte **sintética**, que
gera a base na hora com divergências plantadas, para medir sequência contra paralelo
([ADR-0022](../adr/0022-conciliacao-em-lote-com-structured-concurrency.md)). **Ele não lê os bancos de
verdade.**

```bash
./mvnw -pl lotes/conciliacao -am -DskipTests package
lotes/conciliacao/medir.sh 1000000 4 1     # registros por lado, faixas, rodadas
```

| Divergência | Quer dizer |
| --- | --- |
| `FALTA_NO_RAZAO` | o id está no Pix e não no razão |
| `SOBRA_NO_RAZAO` | o id está no razão e não no Pix |
| `DIVERGENTE` | o id está nos dois, com tipo ou valor diferente |

## Ensaiado

A integridade respondeu `"integro": true` depois de cada falha provocada nos outros roteiros, mas **nunca foi vista
respondendo `false` neste ensaio**: isso quem prova são os testes. A conciliação, com 1 milhão de registros por lado e 4 faixas,
achou as 27 divergências plantadas (7 faltas, 5 sobras e 15 valores diferentes), e os dois jeitos deram o mesmo resultado.

## Em produção

Faltaria uma `Fonte` que lesse os dois bancos, de uma réplica e de uma janela fechada, e uma rotina
diária com alerta. O algoritmo está pronto, e a ligação não.
