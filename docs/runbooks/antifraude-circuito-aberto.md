# Antifraude travado e circuito aberto

## Sintoma

- O painel "Pix e circuito do antifraude" (Grafana, http://localhost:13000) mostra o circuito aberto;
- Pix de valor mais alto ficam `RETIDA`, sem nota do modelo.

**Nada para de funcionar, e o circuito aberto não é o defeito: é a proteção.** Enquanto o antifraude
não responde, Pix de até R$ 500,00 segue, e acima disso fica retido para a analista
([ADR-0033](../adr/0033-pix-retido-antes-do-debito.md)). Aberto, o circuito não faz chamada
nenhuma por 10 s, e o Pix não espera os 800 ms do limite
([ADR-0041](../adr/0041-circuit-breaker-no-antifraude.md)).

**Não há alerta de circuito aberto**, e não há limite por pagador durante a queda: alguém precisa
olhar o painel, e o risco de vários Pix de R$ 500,00 está escrito e aceito na ADR-0041.

## Confirmar

```bash
curl -i localhost:8085/q/health/ready
./scripts/servicos.sh situacao
tail -n 50 .servicos/antifraude.log
```

Para ver o painel, suba a telemetria e o Pix com ela:

```bash
docker compose -f infra/docker/compose.yaml --profile observabilidade up -d --wait
./scripts/servicos.sh parar pix
LEDGERLY_OTEL=1 ./scripts/servicos.sh subir pix
```

## Agir

1. Suba o antifraude de novo: `./scripts/servicos.sh parar antifraude` e `./scripts/servicos.sh subir antifraude`.
2. O circuito volta sozinho: depois dos 10 s, deixa passar pedidos de prova, e fecha depois de 3 que deem certo.
3. **Depois de resolvido, olhe a fila de retidos** no backoffice (http://localhost:8090): o Pix retido
   na queda não anda sozinho, e uma analista libera ou recusa. A lista passa pelo login do backoffice;
   com o token de serviço, `GET /v1/transferencias?situacao=RETIDA` responde 403.

## Resolveu quando

O painel mostra o circuito fechado, e um Pix novo volta a ter nota do modelo.

## Ensaiado

Com o processo do antifraude congelado (`kill -STOP`), um Pix de R$ 50,00 respondeu `201` e ficou
`CONCLUIDA`, e um de R$ 600,00 respondeu `202` e ficou `RETIDA`. Devolvido o processo (`kill -CONT`), o
Pix seguinte concluiu normalmente. O painel não foi aberto neste ensaio: a telemetria estava desligada.

Para medir sob carga, `desempenho/circuito/medir.sh com-circuito` congela o antifraude com `SIGSTOP`,
mede o Pix e devolve o processo com `SIGCONT`. Os números estão na ADR-0041.
