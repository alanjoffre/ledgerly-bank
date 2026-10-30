# Razão fora do ar

## Sintoma

- O Pix responde `202 Accepted`, e não `201`, e fica em andamento;
- o saldo não responde, e `curl -i localhost:8082/actuator/health` falha.

**O dinheiro não se perde, e não é preciso refazer nenhum Pix.** A Saga guarda o passo em que parou
e tenta de novo sozinha, com espera crescente (1 s, 2 s, 4 s... até 1 minuto), sem limite de
tentativas. Refazer o Pix com outra `Idempotency-Key` é o que **criaria** um pagamento em dobro.

## Confirmar

```bash
./scripts/servicos.sh situacao
tail -n 50 .servicos/razao.log
curl -H "Authorization: Bearer $(token)" localhost:8083/v1/transferencias/<id>
```

A transferência mostra a situação, quantas tentativas e a hora da próxima. `CRIADA`, `APROVADA`,
`DEBITADA` ou `ESTORNANDO` são situações em andamento; as demais são finais.

## Agir

1. Veja por que caiu, no log. Se for o banco: `docker compose -f infra/docker/compose.yaml ps postgres`.
2. Suba de novo: `./scripts/servicos.sh subir razao`.
3. Não mexa nos Pix em andamento: eles terminam sozinhos em até 1 minuto.

## Resolveu quando

- a transferência saiu para `CONCLUIDA` (ou `ESTORNADA`, se o crédito foi recusado);
- `GET /v1/integridade` no razão responde `"integro": true` ([como conferir](integridade-e-conciliacao.md)).

## Se sobrar um Pix `EM_ANALISE`

Quer dizer que o estorno foi recusado: a conta que pagou foi encerrada entre o débito e o estorno, e
o dinheiro está parado na Liquidação Pix. Só uma pessoa resolve. O backoffice
(http://localhost:8090) mostra a fila dos mais antigos para os mais novos, e a analista devolve para
outra conta do mesmo titular, com a decisão gravada na trilha de auditoria
([ADR-0030](../adr/0030-devolucao-de-pix-em-analise.md)).

A lista passa pelo login do backoffice: com o token de serviço, `GET /v1/transferencias?situacao=EM_ANALISE`
responde 403. Ninguém deve trabalhar nessa fila com uma credencial de máquina.

## Ensaiado

Com o razão parado, um Pix de R$ 10,00 respondeu `202` e ficou `APROVADA`. Subi o razão, e a
transferência chegou a `CONCLUIDA` 4 s depois, com `"integro": true`.

## Em produção

O serviço já avisa no log depois de 10 tentativas de um mesmo Pix, mas nenhum alerta lê esse log, e
os logs não vão para o painel ([ADR-0042](../adr/0042-opentelemetry-e-otel-lgtm.md)). Em produção, a
idade do Pix mais antigo em andamento precisaria de alerta.
