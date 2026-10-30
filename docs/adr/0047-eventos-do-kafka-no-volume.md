# ADR-0047: Os eventos do Kafka ficam no volume, e o CI confere

- **Status:** Aceita
- **Data:** 2026-10-30

## Contexto

A [ADR-0016](0016-extrato-em-cqrs-no-mongodb.md) diz que apagar o extrato não perde nada, porque o tópico
`razao.lancamentos.v1` guarda os lançamentos para sempre. Isso estava errado no ambiente local, desde a Fase 0.

O compose montava um volume em `/var/lib/kafka/data`, mas a imagem `apache/kafka` grava por padrão em
`/tmp/kafka-logs`, **dentro do contêiner**. O volume ficava vazio, e cada `docker compose down` apagava todos os
eventos, mesmo sem o `-v`. Reconstruir o extrato depois disso apagaria o extrato e não traria nada de volta.

Passou sete fases sem ser visto porque nada testava esse caminho: os testes sobem um Kafka novo a cada execução, e as
demonstrações não reiniciam o ambiente no meio. Apareceu ao ensaiar o runbook do extrato, na Fase 7.

## Decisão

- **`KAFKA_LOG_DIRS: /var/lib/kafka/data`** no compose: o Kafka passa a gravar no volume.
- **O CI confere o efeito, não o texto:** com os tópicos criados, o passo exige a pasta
  `contas.eventos.v1-0` dentro do volume. Conferir só a variável passaria mesmo com um caminho errado.
- **`scripts/reconstruir-extrato.sh` recusa tópico vazio**, antes de apagar qualquer coleção. Um tópico vazio pode ser
  um ambiente novo ou uma perda de eventos, e nos dois casos não há o que reconstruir.

Esta ADR não reescreve a 0016: a decisão de lá continua certa, e o que falhava era a configuração.

## Resultado

Uma mensagem publicada sobreviveu a `down` seguido de `up`. A trava foi exercitada nos dois casos: com o tópico vazio,
recusou e manteve as 2 coleções do extrato; com 35 eventos, reconstruiu e terminou com sucesso.

## Alternativas descartadas

- **Montar o volume em `/tmp/kafka-logs`:** funciona, mas deixa dados duráveis numa pasta cujo nome diz o contrário.
- **Só corrigir o compose, sem o passo no CI:** o defeito ficou sete fases escondido porque nada o olhava.
- **Só um aviso no runbook, sem trava no script:** o aviso depende de alguém ler, com pressa, antes de apagar.

## Consequências

- **Quem já tinha o ambiente de antes fica com dados incoerentes:** o extrato tem o histórico antigo, e o tópico
  começa vazio. Reconstruir é recusado; para voltar a um estado coerente, é preciso apagar os volumes e semear de novo.
- O passo do CI só prova que o Kafka grava no volume. Não há teste automático de `down` seguido de `up`.
- `docker compose down -v` continua apagando os eventos, agora junto com os bancos: é o que o comando pede.
