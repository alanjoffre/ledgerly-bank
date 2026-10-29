# Registros de decisão de arquitetura (ADR)

Uma **ADR** (*Architecture Decision Record*) é um documento curto que registra **uma** decisão
importante: qual era o problema, o que se decidiu, que alternativas foram descartadas e qual preço
se paga pela escolha.

**Por que existe:** daqui a seis meses ninguém lembra *por que* o projeto usa Kafka e não chamadas
HTTP. Sem o registro, a decisão vira "sempre foi assim" — e ou é mantida sem motivo, ou é desfeita
sem que se saiba o que se perde. O formato segue o modelo de Michael Nygard, o mais usado no mercado.

**Regras:**

- ADR aceita não é editada. Se a decisão mudar, escreve-se uma nova ADR que **substitui** a antiga
  (e a antiga ganha o status "Substituída pela ADR-00XX").
- Uma decisão por ADR. Numeração sequencial, nunca reaproveitada.
- Modelo para copiar: [0000-modelo.md](0000-modelo.md).

| Nº | Decisão | Status |
| --- | --- | --- |
| [0001](0001-registrar-decisoes-com-adr.md) | Registrar as decisões de arquitetura com ADR | Aceita |
| [0002](0002-monorepo-maven-multimodulo.md) | Monorepo com Maven multimódulo | Aceita |
| [0003](0003-java-25-lts.md) | Java 25 (LTS) | Aceita |
| [0004](0004-cinco-servicos-com-motivo.md) | Cinco serviços, cada um com um motivo para existir | Aceita |
| [0005](0005-nomes-de-dominio-em-portugues.md) | Nomes de domínio em português (linguagem ubíqua) | Aceita |
| [0006](0006-nuvem-com-custo-zero.md) | Nuvem com custo zero: Terraform validado, sem aplicar | Aceita |
| [0007](0007-dinheiro-com-bigdecimal.md) | Dinheiro com `BigDecimal` e arredondamento bancário | Aceita |
| [0008](0008-persistencia-jdbcclient-no-razao-jpa-em-contas.md) | SQL explícito no razão, JPA no serviço de contas | Aceita |
| [0009](0009-concorrencia-com-trava-pessimista-ordenada.md) | Trava pessimista ordenada no razão, otimista nas contas | Aceita |
| [0010](0010-idempotencia-pela-chave-no-banco.md) | Idempotência pela chave no banco de dados | Aceita |
| [0011](0011-contas-chama-razao-dentro-da-transacao.md) | Contas chama o razão dentro da transação local (provisório) | Substituída pela [0014](0014-contas-avisa-o-razao-por-evento.md) |
| [0012](0012-erros-problem-details-e-dinheiro-como-texto.md) | Erros em Problem Details e dinheiro como texto no JSON | Aceita |
| [0013](0013-outbox-para-publicar-eventos.md) | Outbox para publicar eventos | Aceita |
| [0014](0014-contas-avisa-o-razao-por-evento.md) | Contas avisa o razão por evento; só o encerramento continua síncrono | Aceita |
| [0015](0015-saga-orquestrada-no-pix.md) | Saga orquestrada no Pix, com conta de liquidação e estorno | Aceita |
| [0016](0016-extrato-em-cqrs-no-mongodb.md) | Extrato em CQRS, num modelo de leitura no MongoDB | Aceita |
| [0017](0017-quarkus-no-pix.md) | Quarkus no Pix, com a comparação medida contra o Spring Boot | Aceita |
| [0018](0018-redis-na-frente-do-banco-no-pix.md) | Redis como trava rápida na frente do banco, no Pix | Aceita |
| [0019](0019-contas-quentes-em-subcontas.md) | Dividir as contas quentes do razão em sub-contas | Aceita |
| [0020](0020-virtual-threads-medidas-no-razao.md) | Threads de plataforma no razão: as *virtual threads* medidas e deixadas de lado | Aceita |
| [0021](0021-teste-de-carga-com-gatling.md) | Teste de carga com o Gatling, em Java, fora do build | Aceita |
| [0022](0022-conciliacao-em-lote-com-structured-concurrency.md) | Conciliação em lote com *Structured Concurrency* e *Scoped Values* | Aceita |
| [0023](0023-micro-benchmarks-com-jmh.md) | Micro-benchmarks com o JMH | Aceita |
| [0024](0024-keycloak-e-realm-como-codigo.md) | Keycloak como servidor de identidade, com o realm como código | Aceita |
| [0025](0025-bff-um-por-front-end.md) | Um BFF por front-end, com o token fora do navegador | Aceita |
| [0026](0026-bola-com-404.md) | Autorização por objeto, e o recurso de outro cliente responde 404 | Aceita |
| [0027](0027-token-de-servico-e-razao-sem-rota.md) | Token de serviço entre serviços, e o razão sem rota pública | Aceita |
| [0028](0028-gateway-no-boot-4-0.md) | O gateway no Spring Boot 4.0, enquanto o Spring Cloud do 4.1 não sai | Aceita |
| [0029](0029-react-vite-vitest-playwright.md) | Telas em React + TypeScript + Vite, testadas com Vitest e Playwright | Aceita |
| [0030](0030-devolucao-de-pix-em-analise.md) | Devolução de Pix em análise pelo backoffice, com trilha de auditoria | Aceita |
| [0031](0031-as-duas-corridas-dos-testes-instaveis.md) | As duas corridas por trás dos testes instáveis da Fase 4 | Aceita |
