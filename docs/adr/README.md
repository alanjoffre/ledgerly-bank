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
| [0011](0011-contas-chama-razao-dentro-da-transacao.md) | Contas chama o razão dentro da transação local (provisório) | Aceita (sai na Fase 2) |
| [0012](0012-erros-problem-details-e-dinheiro-como-texto.md) | Erros em Problem Details e dinheiro como texto no JSON | Aceita |
