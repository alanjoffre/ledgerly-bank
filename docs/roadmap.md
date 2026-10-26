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

## ⬜ Fase 1 — Domínio e razão contábil (Spring Boot)

- Serviço **contas**: abertura, bloqueio e encerramento de conta
- Serviço **razao**: lançamentos em **partidas dobradas**, saldo derivado dos lançamentos
- DDD (agregados, *value objects*, eventos de domínio) e arquitetura hexagonal
- **Idempotência** de operações
- Prova de concorrência: 1.000 débitos simultâneos, saldo nunca negativo, nenhum gasto duplo
- PostgreSQL com Flyway; testes de integração com Testcontainers

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
