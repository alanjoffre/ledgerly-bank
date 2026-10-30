# Avaliação do assistente (evals)

Os testes do build provam que o **código** do assistente está certo: a trava de entrada, a trava de
saída, as ferramentas, a busca na ajuda e o caminho inteiro. Eles usam um modelo de linguagem falso,
com respostas roteirizadas. Isso deixa o build rápido e igual em toda rodada.

O que esses testes **não** dizem é se o modelo de verdade responde bem. Isso se mede de outro jeito,
com uma **avaliação** (no mercado, *eval*): um conjunto fixo de perguntas, cada uma com o fato que a
resposta tem de trazer, rodado contra o modelo de verdade. O resultado é uma taxa de acerto, não um
"passou ou falhou" (ADR-0037).

## O que tem aqui

| Arquivo | O que é |
| --- | --- |
| [`assistente/conjunto.json`](assistente/conjunto.json) | 30 perguntas e os dados sintéticos da cliente Ana, que tem duas contas. O "hoje" é fixo (28/10/2026), para "o mês passado" dar sempre a mesma resposta. |
| [`assistente/resultados/`](assistente/resultados/) | Uma rodada por arquivo (`AAAA-MM-DD-modelo.md` e `.json`): a taxa de acerto, por categoria, o tempo de cada pergunta e cada resposta. |

Cada pergunta diz:

- **`deve`**: o que a resposta tem de trazer. Alternativas vão separadas por `|`. A conferência não
  diferencia maiúsculas nem acentos;
- **`naoPode`**: o que a resposta não pode trazer, por exemplo um valor de outra conta, ou o limite
  do antifraude, que o banco não divulga;
- **`origens`**: de onde a resposta pode vir. `MODELO_DE_LINGUAGEM` é uma resposta normal. `RECUSA`
  é a trava de entrada, que barra a pergunta antes do modelo. `RESERVA` é o texto padrão, usado
  quando a trava de saída barrou um valor que não veio do extrato;
- **`fonte`**: o artigo de ajuda que tem de ter sido encontrado (RAG).

As categorias:

| Categoria | Perguntas | O que mede |
| --- | --- | --- |
| saldo | 4 | chamar a ferramenta certa, para a conta certa; nunca mostrar a conta de outra pessoa |
| lancamentos | 10 | escolher o período e copiar o total que a ferramenta já somou |
| ajuda | 11 | achar o artigo certo e responder só com o que está nele |
| seguranca | 5 | tentativa de trocar as instruções, pedido de Pix ou bloqueio, assunto de fora |

## Como rodar

O executor é uma classe de teste, `AvaliacaoDoAssistente`. O build normal não a roda: o nome não
termina em `Test` nem em `IT`, e o CI não tem placa de vídeo para o Ollama. Ela sobe o assistente
com o Ollama de verdade, o pgvector de verdade (Testcontainers) e o contas e o extrato falsos, com
os dados do conjunto.

```bash
docker compose -f infra/docker/compose.yaml --profile ia up -d --wait ollama
./scripts/modelos.sh                      # uma vez: baixa qwen3:4b-instruct, gemma3:4b e bge-m3
./mvnw -pl servicos/assistente test -Dtest=AvaliacaoDoAssistente -Dsurefire.failIfNoSpecifiedTests=false
```

Opções:

- `-Dassistente.linguagem.modelo=qwen3:4b`: roda com outro modelo (aqui, o que "pensa"), para comparar;
- `-Davaliacao.minimo=0.8`: falha se a taxa de acerto ficar abaixo de 80%.

Antes das 30, uma pergunta de aquecimento (fora da conta e do tempo) paga a carga do modelo na
placa de vídeo e a indexação dos artigos de ajuda.

## Por que fora do CI

- **Custo zero:** o modelo roda no Ollama local, na placa de vídeo. O runner do CI não tem placa, e
  um modelo pago quebraria a regra do projeto.
- **Não é determinístico:** a mesma pergunta pode sair com outras palavras. Um teste que às vezes
  falha ensina a ignorar teste vermelho. O CI fica com o que se repete igual; a avaliação mede
  tendência.
- **O resultado entra no Git:** cada rodada fica em `resultados/`. Trocar de modelo, de instrução ou
  de artigo mostra, no diff, o que melhorou e o que piorou.
