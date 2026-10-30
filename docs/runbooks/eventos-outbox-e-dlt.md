# Eventos: outbox e DLT

Dois lugares onde um evento pode ficar parado:

- no **outbox** (contas, razão, Pix): a tabela de saída. O evento é gravado na mesma transação do
  dado, e um publicador leva ao Kafka depois ([ADR-0013](../adr/0013-outbox-para-publicar-eventos.md));
- na **DLT** (*dead letter topic*, "tópico de mensagens mortas"): `<tópico>.dlt`. É para onde vai a
  mensagem que o consumidor não consegue processar, para não travar as que vêm atrás.

## Outbox: evento que não sai

**Sintoma:** o razão não recebeu a conta nova, ou o extrato não recebeu o lançamento. Em geral, o
Kafka está fora. Quando ele volta, o publicador esvazia a fila sozinho, e nada se perde.

```bash
docker compose -f infra/docker/compose.yaml ps kafka
docker compose -f infra/docker/compose.yaml exec -T postgres \
  psql -U ledgerly_admin -d razao -c "SELECT count(*), min(criado_em) FROM outbox WHERE publicado_em IS NULL"
```

Troque `razao` por `contas` ou `pix` para os outros bancos. **Resolveu quando** a contagem chega a 0.

## DLT: mensagem morta

**Sintoma:** o evento não chegou ao destino e o consumidor segue trabalhando. Quem manda para a DLT:

| Serviço | Vai para a DLT | Não vai |
| --- | --- | --- |
| razão | na hora, se a falha não passa (JSON inválido, regra recusada); depois de 4 entregas (a primeira e 3 repetições), nas demais | |
| extrato | na hora, se a mensagem está ruim (JSON quebrado, campo faltando) | banco fora: tenta de novo sem desistir, porque extrato sem um lançamento é pior que extrato atrasado |

Ver as mensagens e o motivo, que vem nos cabeçalhos:

```bash
docker compose -f infra/docker/compose.yaml exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:9092 --topic razao.lancamentos.v1.dlt --from-beginning --timeout-ms 5000 \
  --formatter-property print.headers=true
```

O motivo está em `kafka_dlt-exception-message`, e o tópico e o grupo de origem em
`kafka_dlt-original-topic` e `kafka_dlt-original-consumer-group`. Os cabeçalhos de partição, posição e
hora saem como bytes ilegíveis no terminal. Ao fim dos 5 s, o consumidor pode imprimir um `ERROR` com
`TimeoutException`: é só o efeito do `--timeout-ms`, não um defeito.

Também aparecem no Kafka UI (`docker compose -f infra/docker/compose.yaml --profile ferramentas up -d`,
http://localhost:18080).

## Agir

1. **Entenda a causa antes.** Reenviar sem corrigir só devolve a mensagem para a DLT.
2. Se o defeito for de código, corrija e suba a versão nova.
3. No extrato, o caminho seguro é [reconstruir](extrato-atraso-e-reconstrucao.md): ele relê tudo, e é
   idempotente.
4. No razão, não há ferramenta de reenvio. Isso é uma lacuna conhecida: republicar à mão exige
   cuidado, e a conferência é a [integridade](integridade-e-conciliacao.md), que deve seguir `true`.

## Ensaiado

Com o Kafka parado, um Pix concluiu (`201`), e o outbox ficou com 2 eventos pendentes no razão e 1 no
Pix. Com o Kafka de volta, o outbox do razão zerou em 4 s. Uma mensagem que não é JSON, publicada em
`contas.eventos.v1` e em `razao.lancamentos.v1`, foi parar na DLT de cada tópico, com o motivo nos
cabeçalhos.

## Em produção

Faltariam um alerta para mensagem nova em DLT e uma ferramenta de reenvio com trilha de auditoria.
Hoje, ninguém é avisado.
