# Runbooks

Um **runbook** é o roteiro para quem está de plantão: o sintoma, como confirmar, o que fazer e como
saber que resolveu. Foi escrito para ser seguido com pressa, por quem não conhece o código.

| Quando | Runbook |
| --- | --- |
| Subir, parar, atualizar ou voltar uma versão | [Subir, derrubar e voltar versão](subir-derrubar-e-voltar-versao.md) |
| Pix parado em andamento, ou o razão fora do ar | [Razão fora do ar](razao-fora-do-ar.md) |
| Pix lento ou erro de antifraude; circuito aberto no painel | [Antifraude e circuito aberto](antifraude-circuito-aberto.md) |
| Evento em um tópico `.dlt`, ou evento que não sai do outbox | [Eventos: outbox e DLT](eventos-outbox-e-dlt.md) |
| Extrato errado ou atrasado | [Extrato: atraso e reconstrução](extrato-atraso-e-reconstrucao.md) |
| Conferir se o dinheiro bate | [Integridade e conciliação](integridade-e-conciliacao.md) |
| Testes de mutação, contratos e Sonar | [Qualidade](qualidade.md) |

Todos partem da raiz do repositório e valem para o ambiente local: este é um projeto de portfólio,
com dados sintéticos, e nunca teve plantão de verdade. A seção "Ensaiado" de cada roteiro conta a falha
provocada e o que aconteceu, inclusive o que ficou de fora. [Integridade e conciliação](integridade-e-conciliacao.md)
e [Qualidade](qualidade.md) foram executados, mas sem uma falha provocada para o roteiro. O que precisaria
mudar em produção está dito em "Em produção".

Para os `curl` à mão, carregue antes o token de serviço (vale 5 minutos, e a função renova sozinha):

```bash
source scripts/lib/token.sh
```

## Portas

| Serviço | Porta | Saúde |
| --- | --- | --- |
| gateway do internet banking | 8080 | `/actuator/health` |
| gateway do backoffice | 8090 | `/actuator/health` |
| contas | 8081 | `/actuator/health` |
| razão | 8082 | `/actuator/health` |
| Pix | 8083 | `/q/health/ready` |
| extrato | 8084 | `/actuator/health` |
| antifraude | 8085 | `/q/health/ready` |
| assistente | 8086 | `/actuator/health` |
| Keycloak | 18081 | |
| Grafana (perfil `observabilidade`) | 13000 | |
| Kafka UI (perfil `ferramentas`) | 18080 | |
| SonarQube (perfil `qualidade`) | 19000 | |
