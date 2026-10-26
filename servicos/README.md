# Serviços

Os cinco serviços do Ledgerly Bank entram a partir da Fase 1. O motivo de cada um existir
separado está na [ADR-0004](../docs/adr/0004-cinco-servicos-com-motivo.md).

| Pasta | Framework | Fase | Responsabilidade |
| --- | --- | --- | --- |
| `contas/` | Spring Boot | 1 | Cadastro de clientes e contas |
| `razao/` | Spring Boot | 1 | Razão contábil em partidas dobradas |
| `pix/` | Quarkus | 2 | Chaves Pix, transferência, Saga |
| `extrato/` | Spring Boot | 2 | Extrato em tempo real (CQRS, MongoDB) |
| `antifraude/` | Quarkus | 5 | Nota de risco e explicação com IA |
