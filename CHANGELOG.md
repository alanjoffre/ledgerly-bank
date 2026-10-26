# Histórico de versões

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/); versões seguem o
[Versionamento Semântico](https://semver.org/lang/pt-BR/).

## [Não lançado]

### Adicionado — Fase 0 (fundação)

- Monorepo Maven multimódulo com Java 25 e Maven Wrapper.
- Núcleo compartilhado com o *value object* `Dinheiro`: `BigDecimal` na escala da moeda,
  arredondamento bancário, rateio sem perda de centavo e proteção contra mistura de moedas.
- Qualidade no build: avisos do compilador como erro, cobertura mínima de 90% (JaCoCo),
  teste de arquitetura (ArchUnit), teste de propriedade.
- Infraestrutura local (Docker Compose): PostgreSQL 18 com um banco por serviço, MongoDB 8,
  Redis 8 e Kafka 4 em modo KRaft.
- CI no GitHub Actions (build, testes e validação da infraestrutura) e Dependabot.
- Documentação: arquitetura C4, ADRs 0001–0007, roteiro de fases, glossário, guia de ambiente.
