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
- CI no GitHub Actions e Dependabot — configurados e validados localmente (actionlint); a primeira execução no GitHub acontece quando o repositório for publicado
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

## ⬜ Fase 2 — Pix e eventos (Quarkus)

- Serviço **pix**: chaves Pix, transferência, QR Code
- **Saga** orquestrada com compensação (estorno)
- **Outbox Pattern** + Kafka
- Serviço **extrato** em CQRS sobre MongoDB
- Comparação **medida** entre Spring Boot e Quarkus (tempo de subida, memória)

## ⬜ Fase 3 — Concorrência e desempenho

- *Virtual threads*, *Structured Concurrency*, *Scoped Values*
- Processamento em lote: conciliação diária de milhões de lançamentos
- *Benchmarks* com JMH; teste de carga com Gatling ou k6; relatório com números

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
