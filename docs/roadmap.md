# Roteiro de fases

Cada fase termina com: código testado, documentação atualizada, uma *release* no GitHub e um post
no LinkedIn. O projeto é construído em público (*build in public*).

Legenda: ✅ concluída · 🚧 em andamento · ⬜ planejada

## ✅ Fase 0 — Fundação

- Monorepo Maven multimódulo, Java 25, Maven Wrapper
- Núcleo compartilhado com o *Value Object* `Dinheiro` (rateio sem perda de centavo)
- Qualidade desde o primeiro dia: compilador com avisos como erro, cobertura mínima (JaCoCo),
  teste de arquitetura (ArchUnit), teste de propriedade
- Infraestrutura local: PostgreSQL (um banco por serviço), MongoDB, Redis, Kafka (KRaft)
- CI no GitHub Actions (build, testes e validação da infraestrutura) e Dependabot
- Documentação: README, arquitetura C4, ADRs 0001–0007, glossário, guia de ambiente

## ✅ Fase 1 — Domínio e razão contábil (Spring Boot)

- Serviço **razao** ([README](../servicos/razao/README.md)): lançamentos em **partidas dobradas**,
  contas de natureza devedora e credora, extrato com saldo após cada lançamento, conferência de
  integridade (o balanço fecha?)
- Serviço **contas** ([README](../servicos/contas/README.md)): clientes com CPF validado e
  mascarado, conta como máquina de estados (ativa, bloqueada, encerrada)
- DDD e arquitetura hexagonal nos dois, verificada por ArchUnit
- **Concorrência:** trava pessimista ordenada no razão, otimista nas contas — com prova reversa
  (sem a trava, 1.500 saques passam numa conta de R$ 1.000,00)
- **Idempotência** com `Idempotency-Key` — 100 cliques simultâneos viram 1 lançamento
- O PostgreSQL como segunda linha de defesa: `CHECK`, gatilhos de partidas dobradas e de
  imutabilidade, `UNIQUE`
- API REST com erros em Problem Details (RFC 9457)
- 72 testes de unidade (mais 200 rodadas do teste de CPF, uma por CPF gerado) e 55 de integração
  com Testcontainers; cobertura de linhas acima de 97% nos dois serviços
- [Demonstração](guias/demo-fase-1.md) de ponta a ponta com os dois serviços rodando
- ADRs 0008–0012

## ✅ Fase 2 — Pix e eventos (Quarkus)

- Serviço **pix** em Quarkus ([README](../servicos/pix/README.md)): chaves CPF, e-mail, telefone e
  aleatória (o CPF só se for do titular, conferido sem o CPF sair do serviço de contas); QR Code
  estático no padrão **BR Code** (EMV + CRC16), em texto e em SVG
- **Saga orquestrada** com conta de liquidação e estorno ([ADR-0015](adr/0015-saga-orquestrada-no-pix.md)):
  "sem resposta não é recusa" — com o razão fora do ar, o Pix responde `202` e termina sozinho
  quando ele volta; reserva por tempo e retomada automática se o serviço morrer no meio
- **Redis** como trava rápida contra o segundo clique, com o `UNIQUE` do banco como garantia
  ([ADR-0018](adr/0018-redis-na-frente-do-banco-no-pix.md)) — sem Redis, o Pix continua certo
- **Outbox Pattern** + Kafka nos três serviços que publicam ([ADR-0013](adr/0013-outbox-para-publicar-eventos.md)):
  `acks=all`, produtor idempotente, `FOR UPDATE SKIP LOCKED`, testado com o Kafka pausado
- **Contas avisa o razão por evento** ([ADR-0014](adr/0014-contas-avisa-o-razao-por-evento.md)):
  abrir e bloquear conta não dependem mais do razão no ar; só o encerramento continua síncrono
- Serviço **extrato** em **CQRS** sobre MongoDB ([README](../servicos/extrato/README.md),
  [ADR-0016](adr/0016-extrato-em-cqrs-no-mongodb.md)): montado pelos eventos do razão e do Pix, em
  qualquer ordem; reconstruído do zero a partir do tópico
- [Contrato dos eventos](arquitetura/eventos.md): envelope, tópicos, *dead letter topic*
- Comparação **medida** entre Spring Boot e Quarkus, JVM e nativo ([ADR-0017](adr/0017-quarkus-no-pix.md))
- 629 testes no build (490 de unidade, 139 de integração com Testcontainers e Dev Services);
  cobertura de linhas entre 93% e 98% nos quatro serviços
- [Demonstração](guias/demo-fase-2.md) de ponta a ponta com os quatro serviços rodando
- ADRs 0013–0018

## ✅ Fase 3 — Concorrência e desempenho

- **Teste de carga** com o Gatling, em Java ([ADR-0021](adr/0021-teste-de-carga-com-gatling.md)):
  depósitos, envios de Pix e transferências, 64 clientes simultâneos, com a integridade do razão
  conferida depois de cada rodada; os resumos de cada medição ficam no Git
  (`desempenho/resultados/`)
- **Contas quentes em sub-contas** ([ADR-0019](adr/0019-contas-quentes-em-subcontas.md)): o Caixa e
  a Liquidação Pix viraram 16 sub-contas cada. Depósitos de 99 para 489 pedidos/s e envios de Pix de
  114 para 543 (quase 5×), p99 de ~1 s para ~0,25 s, medidos na mesma sessão
- Um buraco fechado no caminho: o razão aceitava **receber e também estornar** o mesmo envio de Pix.
  Agora é no máximo uma saída por envio, na aplicação e num índice único do banco, com teste de
  disputa simultânea
- **Virtual threads medidas** ([ADR-0020](adr/0020-virtual-threads-medidas-no-razao.md)) em rodadas
  alternadas: no razão, as threads de plataforma atenderam de 14% a 28% mais, e viraram o padrão. O
  gargalo de verdade era o pool de conexões: com 64 conexões em vez de 20, a vazão quase dobrou
- **Conciliação em lote** Pix × razão ([ADR-0022](adr/0022-conciliacao-em-lote-com-structured-concurrency.md)):
  *merge join* em memória constante, em paralelo com *Structured Concurrency* e *Scoped Values*.
  100 milhões de registros por lado em menos de 1,5 s, com o mesmo resultado da execução em sequência
- **Micro-benchmarks com o JMH** ([ADR-0023](adr/0023-micro-benchmarks-com-jmh.md)): `BigDecimal` ×
  `long`; `synchronized`, `ReentrantLock`, `AtomicLong` e `LongAdder` sob disputa; *virtual threads* ×
  pools de threads de plataforma, em tarefas de espera e de cálculo
- 670 testes no build (524 de unidade, 146 de integração com Testcontainers e Dev Services);
  cobertura de linhas entre 93% e 98% nos quatro serviços e na conciliação
- [Demonstração](guias/demo-fase-3.md): a mesma carga com 1 e com 16 sub-contas, e a conciliação
- ADRs 0019–0023

## ⬜ Fase 4 — Segurança e front-end React

- Keycloak (OAuth2/OIDC) e API Gateway
- Internet banking e backoffice de fraude em React + TypeScript
- Testes de ponta a ponta com Playwright

## ⬜ Fase 5 — IA aplicada

- Nota de risco de fraude: regras + modelo de ML (ONNX)
- LangChain4j: explicação do alerta para o analista
- Assistente "pergunte ao seu extrato": RAG com pgvector, *tool calling* só de leitura,
  *guardrails* e avaliação das respostas (*evals*)

## ⬜ Fase 6 — Infraestrutura e nuvem

- Imagens Docker *multi-stage* e imagem nativa (GraalVM)
- Kubernetes (k3d) com Helm, HPA e *probes*
- Terraform da AWS validado no CI; LocalStack
- Observabilidade: OpenTelemetry, Prometheus, Grafana, Tempo
- Resiliência: *circuit breaker*, *retry*, *timeout*

## ⬜ Fase 7 — Qualidade e lançamento

- *Mutation testing* (PIT), testes de contrato (Pact), SonarCloud
- *Runbooks* de operação
- Vídeo de demonstração e post final
