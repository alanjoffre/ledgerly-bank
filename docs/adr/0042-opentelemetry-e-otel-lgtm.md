# ADR-0042: OpenTelemetry nos serviços, com o otel-lgtm só quando pedido

- **Status:** Aceita
- **Data:** 2026-10-30

## Contexto

Um Pix passa por quatro serviços. Quando ele demora, os logs de cada um não dizem onde o tempo foi. E o
*circuit breaker* ([ADR-0041](0041-circuit-breaker-no-antifraude.md)) só tem valor se alguém vê que ele
abriu: enquanto está aberto, o Pix sai sem nota de risco.

O projeto tem custo zero ([ADR-0006](0006-nuvem-com-custo-zero.md)) e a máquina tem memória limitada. Os
testes do build não podem depender de um coletor no ar.

## Decisão

**Os serviços geram rastros e métricas com OpenTelemetry e enviam por OTLP a um contêiner `otel-lgtm`, e
tudo isso fica desligado até ser pedido.**

- **Spring Boot:** `spring-boot-starter-opentelemetry`. **Quarkus:** `quarkus-opentelemetry` e
  `quarkus-micrometer-opentelemetry`.
- **Um só destino:** a imagem `grafana/otel-lgtm` (Grafana, Tempo, Prometheus e o coletor), no perfil
  `observabilidade` do compose, fixada por digest. Ela ocupa 3,7 GB; por isso não sobe sozinha.
- **Ligar é explícito:** `LEDGERLY_OTEL=1` em `scripts/servicos.sh` e em `scripts/k8s.sh`. A amostragem
  vem de `OTEL_AMOSTRAGEM`: 100% no padrão local, 10% no chart.
- **O painel é código:** `infra/observabilidade/paineis/pix.json` mostra o estado do circuito, o Pix por
  segundo, o tempo do Pix, as chamadas por resultado do circuito, o p99 por serviço e a memória.
- **Faixas de tempo explícitas no Quarkus:** a ponte do Micrometer para o OpenTelemetry entrega o
  histograma de `http.server.requests` sem nenhuma faixa, e sem faixa não existe p99.
  `percentilesHistogram(true)` não resolveu; `serviceLevelObjectives` (de 5 ms a 5 s) resolveu.

## O que aparece

Um Pix concluído, num único `traceId` (medido durante a carga):

| Serviço | Trecho | Duração |
| --- | --- | --- |
| pix | `POST /v1/transferencias` | 194,5 ms |
| pix → contas | `GET /v1/contas/{id}/titular` | 9,3 ms |
| pix → contas | `GET /v1/contas/{id}/titular` | 9,1 ms |
| pix → antifraude | `POST /v1/avaliacoes` | 29,4 ms |
| pix → razao | `POST /v1/pix/envios` | 20,2 ms |
| pix → razao | `POST /v1/pix/recebimentos` | 35,9 ms |

Fora os trechos do Redis (`set` e `del`), que também aparecem. No painel, o circuito fica vermelho durante
os 40 s em que o antifraude está congelado.

## O custo, medido

16 clientes em sequência, Pix por segundo na fase normal de `desempenho/circuito/medir.sh`, com razao,
contas, antifraude e pix no ar:

| Telemetria | Rodadas (razao, contas e antifraude aquecidos) | p50 |
| --- | --- | --- |
| Desligada | 378, 394, 369, 335 | 39 a 44 ms |
| Amostragem de 100% | 307, 351 | 43 a 49 ms |
| Amostragem de 10% | 345, 284 | 42 a 51 ms |

**O custo ficou dentro do ruído desta medição, e não sei dizer quanto é.** A mesma condição varia 59 Pix/s
de uma rodada para outra, e os 10% saíram piores que os 100%, o que não tem explicação no mecanismo. A
tendência é de algum custo (nenhuma rodada ligada passou da melhor desligada), mas duas rodadas por
condição não medem isso. A fase normal também é quase toda de Pix recusados pela regra de rajada, com
menos trechos que um Pix concluído.

**A primeira medição estava errada.** Com todos os serviços recém-ligados e uma rodada por condição, deu
219 Pix/s desligada, 164 a 100% e 197 a 10%, o que parecia um custo de 25%. A rodada anterior, com esses
serviços aquecidos, tinha dado 396 e levantou a suspeita. O lgtm parado não mudou nada (219 e 226). A rodada de
aquecimento mostrou a causa: sai de cerca de 205 a 228 Pix/s em qualquer condição, ligada ou desligada. Eram as
JVMs aquecendo, e a diferença entre elas não sustenta conclusão nenhuma.

O `medir.sh` religa o pix a cada rodada, então o pix nunca está inteiramente aquecido: o que muda entre uma
rodada fria e uma aquecida é o estado de razao, contas e antifraude, e o `medir.py` só descarta os primeiros 5 s.

## Alternativas consideradas

- **Prometheus, Grafana e Tempo em contêineres separados:** é como se roda em produção, mas são mais
  arquivos e mais memória para um ambiente local, sem mostrar nada a mais.
- **Agente Java do OpenTelemetry:** não exige dependência no código, mas aumenta a subida e não funciona na
  imagem nativa do Pix ([ADR-0038](0038-imagens-multi-stage-e-nativa.md)).
- **Telemetria sempre ligada:** a imagem de 3,7 GB passaria a ser obrigatória para rodar o banco.
- **Só métricas, sem rastros:** diriam que o Pix ficou lento, e não em qual serviço.

## Consequências

- **Positivas:**
  - Um Pix pode ser seguido de ponta a ponta, e o tempo de cada serviço aparece sem instrumentar nada à mão.
  - O circuito aberto fica visível. O alerta em si **não** foi feito: alguém precisa olhar o painel.
  - Quem não liga não paga a imagem, e os testes não dependem de coletor.
- **Negativas e limites conhecidos:**
  - **O rastro para no Kafka:** o que sai pelo *outbox* não continua o `traceId` do Pix, e o consumo dos
    eventos fica fora do rastro dele. Propagar exige guardar o contexto na tabela de *outbox*.
  - **Os logs não vão ao lgtm:** continuam só na saída padrão, sem OTLP.
  - **O custo não está medido com precisão,** como dito acima; a amostragem de 10% no chart é prudência, não
    uma conta.
  - **Mudar de versão pode mudar nomes de métrica,** e o painel é um arquivo que depende deles
    (`http_server_requests_milliseconds_bucket`, `ft_circuitbreaker_state_current`).
  - **Este lgtm é para a máquina local:** guarda pouco histórico e não tem alta disponibilidade. Numa nuvem,
    o destino seria outro, e só o `OTEL_ENDPOINT` muda.
