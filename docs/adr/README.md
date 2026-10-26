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
