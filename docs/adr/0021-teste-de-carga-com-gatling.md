# ADR-0021: Teste de carga com o Gatling, em Java, fora do build

- **Status:** Aceita
- **Data:** 2026-10-28

## Contexto

A Fase 3 precisa de números: quanto o razão aguenta, onde está o gargalo, e se uma mudança melhorou
ou só pareceu melhorar. Os testes de integração provam que o razão está **certo** sob disputa
(ADR-0009); não dizem quantos pedidos por segundo ele atende, nem quanto o cliente espera.

Para isso é preciso um **teste de carga**: disparar muitos pedidos ao mesmo tempo contra o serviço
no ar e medir a **vazão** (pedidos por segundo) e a **latência** (quanto cada pedido demora, nos
percentis p50, p95 e p99: o p99 mostra o cliente azarado, que a média esconde).

## Decisão

Usar o **Gatling** (3.16, pelo plugin do Maven), num módulo próprio, `desempenho/carga`, com a
simulação escrita em **Java**.

- **Modelo fechado** (`constantConcurrentUsers`): há sempre 64 pedidos em andamento; quando um
  termina, outro começa. Mede a vazão máxima com 64 clientes martelando.
- **Três cenários** (`-Dcenario=`): `depositos` (todo pedido passa pelo Caixa), `pix-envios` (pela
  Liquidação Pix) e `transferencias` (entre clientes sorteados, sem conta quente: a referência do
  que o razão faz quando nada disputa a mesma linha).
- **Preparação fora da medição:** antes da carga, a simulação abre 2.000 contas e põe saldo nelas,
  com o `HttpClient` do próprio Java, em *virtual threads*. Depois, confere `GET /v1/integridade`:
  uma medição só vale se o razão continuou íntegro.
- **Critério de validade:** mais de 1% de pedidos com erro reprova a rodada. Pedido recusado
  rápido não é vazão.
- **Fora do `./mvnw verify`:** o módulo compila no build (para não apodrecer), mas a carga só roda
  quando alguém pede: `desempenho/carga/medir.sh <cenário> <rótulo>`. O resumo de cada rodada vai
  para `desempenho/resultados/carga/` (no Git, para comparar depois); o relatório HTML com os
  gráficos fica em `target/` (fora do Git, por ser grande e gerado).

## Alternativas consideradas

- **k6** — muito bom e popular, mas as simulações são em JavaScript e ele é um binário à parte. O
  Gatling roda pelo Maven Wrapper que o projeto já usa, sem instalar nada, e a simulação fica no
  mesmo idioma do resto.
- **JMeter** — o mais antigo do mercado; os testes são XML montados numa interface gráfica,
  difíceis de revisar num *pull request*.
- **Um laço com `HttpClient` e `System.nanoTime()`** — dá um número, mas não os percentis, nem o
  aquecimento, nem o relatório. É reinventar a ferramenta, pior.
- **Rodar no CI a cada envio** — o CI roda em máquinas compartilhadas, cuja velocidade varia de uma
  execução para outra: o número oscilaria sem o código mudar. E custaria minutos de CI.

## Consequências

- **Positivas:** a medição é reprodutível por qualquer um com um comando; o relatório HTML mostra a
  vazão e a latência ao longo do tempo; o gargalo das contas quentes foi achado e medido antes de
  mexer no código (ADR-0019).
- **Negativas:**
  - os números dependem da máquina. Esta roda no WSL com outros contêineres no ar, e a mesma
    configuração variou uns 20% entre sessões. Por isso **só se compara medição da mesma sessão**,
    e cada arquivo de resultado registra quando e onde foi medido;
  - o Gatling, o razão e o PostgreSQL rodam na mesma máquina, disputando a CPU. Num ambiente de
    verdade, o gerador de carga fica em outra máquina. Os números servem para comparar um antes e
    um depois, não como capacidade de produção;
  - o banco de desenvolvimento cresce a cada rodada (milhares de lançamentos). Não atrapalha a
    medição, mas o envio de Pix sem Saga deixa dinheiro na Liquidação Pix; a demonstração da
    Fase 2 passou a comparar o saldo dela com o do início, e não com zero.
