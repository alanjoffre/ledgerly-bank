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

## ✅ Fase 4 — Segurança e front-end React

- **Keycloak** (OAuth2/OIDC) com o realm como código: papéis, pessoas e clientes num JSON
  importado na subida, conferido no CI ([ADR-0024](adr/0024-keycloak-e-realm-como-codigo.md)).
  Todo serviço confere o token: assinatura, emissor, validade e audiência
- **Dois BFFs** (*Backend for Frontend*), um por tela, com o Spring Cloud Gateway
  ([ADR-0025](adr/0025-bff-um-por-front-end.md), [ADR-0028](adr/0028-gateway-no-boot-4-0.md)): login
  com *authorization code* + PKCE, o token guardado no servidor e repassado aos serviços
  (*TokenRelay*), o navegador só com um cookie `HttpOnly`; CSRF de SPA e CSP só com `'self'`. O salto
  pelo BFF custa cerca de 1 ms por pedido (9% menos vazão), medido com o Gatling
- **BOLA** ([ADR-0026](adr/0026-bola-com-404.md)): o cliente só vê o que é dele, e o recurso de outro
  responde 404, igual a um que não existe, no contas, no Pix e no extrato
- **Token de serviço** (*client credentials*) entre os serviços, e o **razão sem rota** no gateway:
  só outro serviço fala com ele ([ADR-0027](adr/0027-token-de-servico-e-razao-sem-rota.md))
- **Devolução de Pix em análise** ([ADR-0030](adr/0030-devolucao-de-pix-em-analise.md)): a analista
  devolve o dinheiro para outra conta do mesmo titular, com justificativa e trilha de auditoria;
  duas analistas ao mesmo tempo resultam em uma devolução só
- **Duas telas em React 19 + TypeScript** ([ADR-0029](adr/0029-react-vite-vitest-playwright.md)): o
  internet banking (contas, extrato, Pix com "confira quem recebe", chaves) e o backoffice de fraude
  (fila de análise, devolução, consulta e bloqueio de conta)
- **Testes de ponta a ponta com o Playwright**: um Chromium de verdade contra o sistema inteiro.
  Acharam **cinco defeitos** que todos os outros testes deixaram passar, entre eles a mensagem do Pix,
  que não chegava ao extrato desde a Fase 2
- 844 testes: 782 no build Java (592 de unidade, 190 de integração), 57 nas telas (Vitest) e 5 de
  ponta a ponta (cerca de 5 s); cobertura de linhas entre 92% e 98,6% nos cinco serviços
- [Demonstração](guias/demo-fase-4.md): as duas telas, a devolução e os testes de ponta a ponta
- ADRs 0024–0030

## ✅ Fase 5 — IA aplicada

- **Antifraude antes do débito** ([ADR-0032](adr/0032-antifraude-regras-e-modelo-onnx.md),
  [ADR-0033](adr/0033-pix-retido-antes-do-debito.md)): nota de 0 a 1000 por um modelo de regressão
  logística treinado em Python e executado na JVM em **ONNX** (p50 ≈ 6,5 µs por Pix), mais as regras
  da rajada e da conta "laranja". Nota a partir de 200, o Pix fica **retido** sem débito, para a
  analista liberar ou recusar; a partir de 900, recusado. Antifraude fora do ar: até R$ 500,00
  segue, acima fica retido. O cliente não vê a nota nem os motivos
- **Modelo escolhido pela métrica certa**: com 2% de fraude, a acurácia engana; a regressão
  logística ganhou das árvores com gradiente na precisão média (0,553 × 0,517), e os limites saíram
  da tabela de limiares medida em 15 mil Pix sintéticos
- **Modelo de linguagem local, custo zero** ([ADR-0034](adr/0034-modelo-de-linguagem-local-ollama.md)):
  Ollama com a placa de vídeo, LangChain4j 1.23; nenhum dado sai da máquina
- **Explicação do Pix retido para a analista** ([ADR-0035](adr/0035-explicacao-com-trava-de-numeros.md)):
  o modelo escreve, e uma trava derruba o texto que traz um número que não está na avaliação
- **Assistente "pergunte ao seu extrato"** ([ADR-0036](adr/0036-assistente-rag-ferramentas-e-travas.md)):
  RAG com pgvector sobre os artigos de ajuda, *tool calling* só de leitura com o token do cliente,
  *guardrails* na entrada e na saída (valor que não veio do extrato não chega ao cliente)
- **Avaliação das respostas** (*evals*, [ADR-0037](adr/0037-avaliacao-do-assistente-fora-do-ci.md)):
  30 perguntas contra o modelo de verdade, fora do CI: **27 de 30** com o `qwen3:4b-instruct-2507`,
  depois de achar 2 defeitos que os testes do build não pegavam (eram 4 de 30)
- 1.083 testes: 1.005 no build Java (765 de unidade, 240 de integração), 71 nas telas (Vitest)
  e 7 de ponta a ponta (Playwright); cobertura de linhas entre 91,1% e 98,2% nos sete serviços e na conciliação
- [Demonstração](guias/demo-fase-5.md): o Pix retido, a explicação, o assistente e a avaliação
- ADRs 0032–0037

## ✅ Fase 6 — Infraestrutura e nuvem

- **Imagens** ([ADR-0038](adr/0038-imagens-multi-stage-e-nativa.md)): um `Dockerfile` *multi-stage*; mudar uma linha
  de código troca uma camada de 594 kB. Pix nativo de 219 MB, que sobe em ~0,1 s (~2,5 s na JVM)
- **Kubernetes local** ([ADR-0039](adr/0039-kubernetes-local-com-k3d-e-helm.md)): k3d e Helm; `./scripts/k8s.sh subir`
  leva 62 s do zero. HPA no pix (2→5) e no razao (1→3), com 180.000 pedidos sem erro; pod morto no meio
  de 320 Pix sem perda; troca de versão sob carga de 586 erros (sem sondas) para 0
- **Terraform da AWS** ([ADR-0040](adr/0040-terraform-validado-e-simulador-local.md)): 12 módulos e 230 recursos no plano,
  validados sem conta e com custo zero, com o simulador Moto no lugar do LocalStack. Nunca aplicado numa
  conta real
- **Circuit breaker** ([ADR-0041](adr/0041-circuit-breaker-no-antifraude.md)): com o antifraude travado, de 18–19 para
  121–130 Pix/s, e p50 de ~830 para ~105 ms. Sem *retry*, de propósito. O limite por pagador durante a
  queda ficou de fora, e a ADR registra
- **OpenTelemetry** ([ADR-0042](adr/0042-opentelemetry-e-otel-lgtm.md)): um rastro único por Pix e o circuito num painel do
  Grafana, ligados só quando pedidos. O custo ficou dentro do ruído da medição
- 1.091 testes: 1.011 no build Java (765 de unidade, 246 de integração), 73 nas telas (Vitest) e 7 de ponta a
  ponta (Playwright), estes também contra o cluster, com o Pix nativo
- 2 novos workflows: `Imagens e chart` e `Nuvem`
- [Demonstração](guias/demo-fase-6.md): imagens, cluster, Terraform, circuito e painel
- ADRs 0038–0042

## ⬜ Fase 7 — Qualidade e lançamento

- *Mutation testing* (PIT), testes de contrato (Pact), SonarCloud
- *Runbooks* de operação
- Vídeo de demonstração e post final
