# Serviços

O motivo de cada serviço existir separado está na [ADR-0004](../docs/adr/0004-cinco-servicos-com-motivo.md).

| Serviço | Framework | Porta | Fase | Status | Responsabilidade |
|---|---|---|---|---|---|
| [contas](contas/README.md) | Spring Boot + JPA | 8081 | 1 | ✅ | Clientes e ciclo de vida da conta |
| [razao](razao/README.md) | Spring Boot + JDBC | 8082 | 1 | ✅ | Razão contábil em partidas dobradas, saldo |
| `pix/` | Quarkus | — | 2 | ⬜ | Chaves Pix, transferência, Saga |
| `extrato/` | Spring Boot | — | 2 | ⬜ | Extrato em tempo real (CQRS, MongoDB) |
| `antifraude/` | Quarkus | — | 5 | ⬜ | Nota de risco e explicação com IA |
