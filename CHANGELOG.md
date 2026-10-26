# Histórico de versões

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/); versões seguem o
[Versionamento Semântico](https://semver.org/lang/pt-BR/).

## [Não lançado]

## [0.1.0] — 2026-10-26

### Adicionado — Fase 1 (razão contábil e contas)

- Serviço **razao** (Spring Boot + JdbcClient): partidas dobradas, contas devedoras e credoras,
  depósito, saque, transferência, extrato e conferência de integridade.
- Serviço **contas** (Spring Boot + JPA): clientes com CPF validado e mascarado, conta como máquina
  de estados, saldo consultado no razão.
- Trava pessimista ordenada no razão e otimista nas contas, com testes de concorrência e prova
  reversa (sem a trava, os testes acusam gasto duplo e atualização perdida).
- Idempotência por `Idempotency-Key`, resistente a pedidos simultâneos.
- Regras garantidas também pelo PostgreSQL: `CHECK` de saldo, gatilhos de partidas dobradas e de
  imutabilidade, `UNIQUE` da chave.
- Erros em Problem Details (RFC 9457), em português.
- Script e guia de demonstração de ponta a ponta.
- ADRs 0008–0012.

### Corrigido — Fase 1

- O horário do lançamento passou a ter a precisão do PostgreSQL (microssegundos): antes, a resposta
  de um pedido repetido saía com o horário diferente da original. Achado pelo teste de integração.

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

[Não lançado]: https://github.com/alanjoffre/ledgerly-bank/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/alanjoffre/ledgerly-bank/releases/tag/v0.1.0
