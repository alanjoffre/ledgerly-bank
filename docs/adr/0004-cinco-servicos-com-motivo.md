# ADR-0004: Cinco serviços, cada um com um motivo para existir

- **Status:** Aceita
- **Data:** 2026-10-26

## Contexto

Microsserviços resolvem problemas de **escala e de organização** (times independentes, partes que
crescem em ritmos diferentes), mas cobram caro: rede entre serviços, consistência eventual, mais
coisas para monitorar. Um erro comum é quebrar o sistema em dezenas de serviços sem motivo — o
chamado "monólito distribuído", que tem os custos dos dois mundos e as vantagens de nenhum.

## Decisão

Cinco serviços, cada um separado por um motivo concreto:

| Serviço | Motivo para ser separado |
| --- | --- |
| **contas** (Spring Boot) | Cadastro de cliente e conta muda pouco e tem regras próprias (abertura, bloqueio, encerramento). |
| **razao** (Spring Boot) | O razão contábil é o coração: exige consistência forte e é o ponto mais disputado (concorrência). Isolá-lo protege o dado mais crítico. |
| **pix** (Quarkus, imagem nativa) | Tráfego em picos (dia 5, Black Friday): precisa subir rápido e escalar horizontalmente. |
| **antifraude** (Quarkus + IA) | Responde em milissegundos no caminho do Pix e evolui no ritmo dos modelos de IA, separado das regras contábeis. |
| **extrato** (CQRS) | A leitura é muito mais frequente que a escrita; um modelo de leitura próprio (MongoDB) não sobrecarrega o razão. |

Dentro de cada serviço, a organização é **hexagonal** (*Ports & Adapters*): o domínio no centro,
sem depender de framework.

## Alternativas consideradas

- **Monólito modular** — seria a escolha certa para um produto novo com uma pessoa: menos
  infraestrutura, transações locais. Foi descartado porque o objetivo do projeto é justamente
  demonstrar os problemas de sistemas distribuídos (Saga, Outbox, idempotência) e dois frameworks
  (Spring e Quarkus) — e isso é dito com honestidade aqui.
- **Quinze ou mais serviços** (um para cliente, outro para endereço, outro para tarifa...) — cada
  separação a mais é custo de rede e de consistência sem ganho.

## Consequências

- **Positivas:** cada serviço escala e evolui sozinho; falha no antifraude não derruba o extrato.
- **Negativas:** não há transação de banco entre serviços — uma transferência Pix que passa por
  pix, antifraude e razão precisa de **Saga** (passos com desfazimento) e **Outbox** (evento
  gravado na mesma transação do dado). Esses padrões entram na Fase 2.
