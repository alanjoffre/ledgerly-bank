# Serviços

O motivo de cada serviço existir separado está na [ADR-0004](../docs/adr/0004-cinco-servicos-com-motivo.md).

| Serviço | Framework | Porta | Fase | Status | Responsabilidade |
|---|---|---|---|---|---|
| [contas](contas/README.md) | Spring Boot + JPA | 8081 | 1 | ✅ | Clientes e ciclo de vida da conta |
| [razao](razao/README.md) | Spring Boot + JDBC | 8082 | 1 | ✅ | Razão contábil em partidas dobradas, saldo |
| [pix](pix/README.md) | Quarkus + JDBC + Redis | 8083 | 2 | ✅ | Chaves Pix, QR Code, transferência (Saga com estorno) |
| [extrato](extrato/README.md) | Spring Boot + MongoDB | 8084 | 2 | ✅ | Extrato montado pelos eventos (CQRS) |
| [gateway](gateway/README.md) | Spring Cloud Gateway (Web MVC) | 8080 · 8090 | 4 | ✅ | Os dois BFFs: login no Keycloak, sessão, CSRF e as telas; o token fica aqui, longe do navegador |
| `antifraude/` | Quarkus | — | 5 | ⬜ | Nota de risco e explicação com IA |

Os quatro conversam por HTTP (comandos) e pelo Kafka (eventos): o formato de cada um está no
[contrato dos eventos](../docs/arquitetura/eventos.md). Desde a Fase 4, todo pedido leva um token do
Keycloak: o de uma pessoa, que chega pelo gateway, ou o do próprio serviço (*client credentials*).
O razão só aceita o de serviço e não tem rota no gateway
([ADR-0027](../docs/adr/0027-token-de-servico-e-razao-sem-rota.md)).

Para subir todos de uma vez (os quatro e os dois gateways): `./scripts/servicos.sh subir`.
