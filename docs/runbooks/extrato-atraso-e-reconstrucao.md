# Extrato: atraso e reconstrução

O extrato é um **modelo de leitura** no MongoDB, derivado dos eventos do razão
([ADR-0016](../adr/0016-extrato-em-cqrs-no-mongodb.md)). A fonte da verdade é o razão: um extrato
errado não muda o saldo de ninguém, e apagar o extrato não perde nada.

## Atrasado ou errado?

**Atrasado** é o grupo com *lag* (eventos ainda não lidos):

```bash
docker compose -f infra/docker/compose.yaml exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:9092 --describe --group extrato
```

Se a coluna `LAG` cresce, veja o MongoDB (`docker compose -f infra/docker/compose.yaml ps mongo`) e o
serviço (`tail -n 50 .servicos/extrato.log`). Com o MongoDB fora, o consumidor espera e tenta de novo
sem desistir: ao voltar, recupera sozinho.

**Errado** é `LAG` em 0 e linhas que não batem com o razão. Aí é reconstruir.

## Reconstruir

```bash
./scripts/servicos.sh parar extrato     # o Kafka só muda os offsets de um grupo sem consumidores
./scripts/reconstruir-extrato.sh        # recusa tópico vazio; apaga as coleções e volta o grupo ao início
./scripts/servicos.sh subir extrato     # relê tudo
```

**A reconstrução só traz o que está no tópico.** Por isso o script confere antes o tópico
`razao.lancamentos.v1` e não apaga nada se ele estiver vazio. Até a Fase 6 o Kafka gravava dentro do
contêiner, e um `down` apagava os eventos
([ADR-0047](../adr/0047-eventos-do-kafka-no-volume.md)); reconstruir nesse estado perderia o extrato.

**Enquanto relê, o extrato mostra dados parciais.** Em produção, isso pediria aviso na tela ou
reconstrução em uma coleção à parte, trocada só no fim.

## Resolveu quando

`LAG` em 0, e o saldo da conta de teste é igual no extrato e no razão:

```bash
curl -H "Authorization: Bearer $(token)" localhost:8084/v1/contas/<id>/saldo
curl -H "Authorization: Bearer $(token)" localhost:8082/v1/contas/<id>
```

## Ensaiado

Antes da reconstrução, a conta de teste tinha saldo de R$ 979,00 nos dois serviços, com `sequencia` 9
no extrato. Depois de apagar e reler, voltou com o mesmo saldo e a mesma sequência.
