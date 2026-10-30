# Guia: demonstração da Fase 5

Mostra, na sua máquina, a IA aplicada do banco:

- um **Pix suspeito fica retido** antes do débito: o antifraude dá a nota, e a analista Carla vê, no
  backoffice, os motivos e uma explicação escrita por um modelo de linguagem local;
- a Ana pergunta ao **assistente** do internet banking pelo saldo e pede um Pix: ele responde o
  saldo e recusa o Pix, porque só lê;
- a **avaliação** do assistente: 30 perguntas fixas contra o modelo de verdade.

Leva uns 20 minutos na primeira vez (a maior parte é baixar os modelos, cerca de 7 GB). Precisa de
uma placa de vídeo NVIDIA para o Ollama; sem ela, o banco funciona e a IA generativa responde os
textos de reserva (passo 6).

O que está por trás: o antifraude com regras e um modelo ONNX
([ADR-0032](../adr/0032-antifraude-regras-e-modelo-onnx.md)), o Pix retido antes do débito
([ADR-0033](../adr/0033-pix-retido-antes-do-debito.md)), o Ollama
([ADR-0034](../adr/0034-modelo-de-linguagem-local-ollama.md)), a trava de números da explicação
([ADR-0035](../adr/0035-explicacao-com-trava-de-numeros.md)), o assistente
([ADR-0036](../adr/0036-assistente-rag-ferramentas-e-travas.md)) e a avaliação
([ADR-0037](../adr/0037-avaliacao-do-assistente-fora-do-ci.md)).

## 1. Subir tudo

Na raiz do projeto, como na [demonstração da Fase 4](demo-fase-4.md), agora com o perfil `ia`:

```bash
docker compose -f infra/docker/compose.yaml --profile ia up -d --wait   # bancos, Kafka, Keycloak e o Ollama
./scripts/modelos.sh                                       # uma vez: qwen3:4b-instruct, gemma3:4b e bge-m3
./mvnw package -DskipTests
(cd web && npm ci && npm run construir)
./scripts/servicos.sh subir                                # os seis serviços e os dois gateways
./scripts/semear.sh
```

Os três modelos não cabem juntos na placa de 6 GB: o `bge-m3` (0,7 GB) fica; quando o `gemma3:4b`
(2,9 GB) sobe para escrever uma explicação, o Ollama tira o modelo do assistente da placa, e a
próxima pergunta ao assistente paga a carga de novo.

## 2. Um Pix retido

```bash
./scripts/pix-retido.sh                 # a Ana paga R$ 1.500,00 a uma conta do Bruno aberta agora
```

O script abre uma conta nova para o Bruno (a conta "laranja") e manda o Pix pela API. Quem retém é
o antifraude de verdade: conta aberta há menos de 2 dias, que nunca recebeu deste pagador, e valor a
partir de R$ 1.000,00. A regra põe a nota em 500 (SEGURAR). **De madrugada, a nota sobe**: o modelo
ONNX soma o sinal do horário. Medido às 04:32 de 30/10/2026: **nota 882**. A partir de 900 o antifraude
recusa sozinho; por isso o script usa R$ 1.500,00, e não R$ 2.000,00.

Nenhum dinheiro saiu: o Pix fica `RETIDA` antes do débito.

## 3. O backoffice: a analista decide

Abra http://localhost:8090 e entre como `carla` (senha `carla-local`).

1. **Pix retidos** lista o Pix. Clique em **Analisar**.
2. **Por que foi retido** mostra a nota, os motivos e a tabela do que o antifraude viu (valor,
   média do pagador, Pix na última hora, se já pagou esta conta, madrugada, idade da conta).
3. A **explicação** aparece em 2 a 3 frases, escritas pelo `gemma3:4b`. Na captura do post:

   > A operação chamou a atenção por envolver um valor elevado, R$ 1.500,00, em uma conta
   > recém-criada com apenas um dia de idade. O fato de o Pix ter sido realizado em horário
   > incomum, durante a madrugada, e de ser o primeiro pagamento para essa conta, também gerou uma
   > pontuação alta no antifraude. […]

   Todo número do texto tem de existir na avaliação; um número inventado derruba o texto e vale um
   texto fixo, montado com os motivos.
4. Escreva a justificativa (pelo menos 10 caracteres; fica na auditoria) e clique em **Liberar** ou
   **Recusar**. Liberado, o Pix segue até `CONCLUIDA`; recusado, termina `REJEITADA`, sem dinheiro
   movido.

**Medido:** com o modelo já na placa, a explicação leva cerca de 2,5 s. Na **carga a frio** (o
`gemma3` subindo para a placa no lugar do modelo do assistente), a primeira leitura passou do tempo
limite de 30 s e a tela mostrou o texto fixo; ao abrir de novo, veio o texto do modelo. É o
comportamento previsto: o texto fixo de quando o modelo está lento não é gravado, e a próxima
leitura tenta outra vez.

## 4. O internet banking: o que a Ana vê

Entre em http://localhost:8080 como `ana` (senha `ana-local`).

- No **Enviar Pix**, um Pix retido aparece como "Pix retido para análise de segurança." A Ana **não**
  vê a nota nem os motivos: se o cliente lê, o golpista lê, e aprende a ficar abaixo do limite.
- **Pergunte ao assistente.** Na captura do post:

  | Pergunta | Resposta |
  | --- | --- |
  | Qual é o meu saldo? | O seu saldo na conta 100035-7 é R$ 1.366,63. |
  | Faça um Pix de R$ 50,00 para o Bruno. | Só posso ajudar com a conta do cliente e as regras do Pix e da conta. Não faço Pix nem posso realizar transações. |

  Embaixo de cada resposta, "Com base em:" lista os artigos de ajuda que a busca achou (RAG). O
  assistente tem três ferramentas, todas só de leitura, chamadas com o token da própria Ana: pedir
  um Pix não tem o que chamar.

## 5. A avaliação do assistente

```bash
./mvnw -pl servicos/assistente test -Dtest=AvaliacaoDoAssistente -Dsurefire.failIfNoSpecifiedTests=false
```

Sobe o assistente com o Ollama e o pgvector de verdade, faz as 30 perguntas do
[conjunto](../../avaliacao/assistente/conjunto.json) e grava o resultado em
`avaliacao/assistente/resultados/`. A rodada de 30/10/2026:

```text
Acertos: 27 de 30 · tempo por pergunta: p50 1351 ms, p95 2750 ms, máximo 2981 ms
saldo 3/4 · lancamentos 9/10 · ajuda 11/11 · seguranca 4/5
```

As três falhas e os dois defeitos que a avaliação achou **antes** desta rodada (a resposta vazia do
modelo que "pensa" e o esquema de ferramenta que o Ollama recusava, com 4 de 30) estão explicados no
[resultado](../../avaliacao/assistente/resultados/2026-10-30-qwen3-4b-instruct-2507-q4-K-M.md).
Nenhum dos dois aparecia nos testes do build: eles usam um modelo falso, que aceita qualquer esquema.

## 6. Sem placa de vídeo

Suba sem o perfil `ia`. O antifraude e o Pix retido funcionam iguais (o modelo ONNX roda dentro da
JVM, sem placa). A explicação vem sempre no texto fixo, e o assistente responde a mensagem de
reserva. Nenhum teste do build depende do Ollama.

## 7. Os testes de ponta a ponta

```bash
cd web/e2e && npm run e2e
```

Sete testes, cerca de 12 s (medido: 13,4 s e 11,4 s). Os dois do Pix retido usam o
`pix-retido.sh` e conferem a nota com uma expressão (`Nota \d{3} de 1000`), porque ela muda com a
hora. Rode com `npm run e2e`, e não com `npx playwright test` dentro de `web/`: lá ele pega também
os arquivos do Vitest.

## 8. Desligar

```bash
./scripts/servicos.sh parar
docker compose -f infra/docker/compose.yaml --profile ia down   # os dados e os modelos ficam; -v apaga tudo
```
