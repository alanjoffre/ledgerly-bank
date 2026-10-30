# ADR-0037: A qualidade do assistente se mede com uma avaliação (eval) fora do CI; o CI testa o código

- **Status:** Aceita
- **Data:** 2026-10-29

## Contexto

Os testes do assistente (ADR-0036) usam um modelo de linguagem **falso**, com respostas roteirizadas.
Eles provam que o **código** está certo: a trava de entrada barra, a trava de saída derruba o valor
inventado, as ferramentas só leem as contas do dono, e a reserva entra quando o modelo cai.

Eles não dizem se o modelo **de verdade** responde bem: se acha a conta certa, se copia o total em
vez de somar errado, se recusa o assunto de fora, se não conta o limite do antifraude. Isso não cabe
num teste comum, por três motivos:

- o modelo roda no Ollama, com placa de vídeo; o runner do CI não tem placa, e um modelo pago quebra o
  custo zero;
- a mesma pergunta pode sair com outras palavras: um teste de texto exato falharia à toa;
- um modelo erra **às vezes**. O que importa é a taxa de acerto e se ela sobe ou desce com cada
  mudança, não um "passou ou falhou".

## Decisão

**Uma avaliação** (no mercado, *eval*), em [`avaliacao/`](../../avaliacao/README.md):

- **um conjunto fixo** (`avaliacao/assistente/conjunto.json`): 30 perguntas e os dados sintéticos de
  uma cliente com duas contas, com o "hoje" fixo (28/10/2026), para "o mês passado" dar sempre a
  mesma resposta. Quatro categorias: saldo (4), lançamentos (10), ajuda (11) e segurança (5);
- **cada pergunta diz o que conferir**, sem exigir a frase exata: `deve` (o que a resposta tem de
  trazer, com alternativas separadas por `|`), `naoPode` (por exemplo o saldo da outra conta, ou os
  limites do antifraude), `origens` (resposta normal, recusa ou reserva) e `fonte` (o artigo que a
  busca tinha de achar). A conferência não diferencia maiúsculas nem acentos;
- **o executor** é uma classe de teste, `AvaliacaoDoAssistente`, que o build não roda (o nome não
  termina em `Test` nem `IT`). Ela sobe o assistente inteiro com o Ollama de verdade, o pgvector de
  verdade (Testcontainers) e o contas e o extrato falsos com os dados do conjunto, faz as 30
  perguntas pelo HTTP, com o token da cliente, e mede a taxa de acerto e o tempo (p50, p95 e o
  máximo). Antes das 30, uma pergunta de aquecimento, fora da conta, paga a carga do modelo na
  placa e a indexação;
- **o resultado entra no Git**, um arquivo por rodada (`resultados/AAAA-MM-DD-modelo.md` e `.json`),
  com cada resposta e o que faltou. Trocar o modelo, as instruções ou um artigo mostra, no diff, o que
  melhorou e o que piorou;
- `-Davaliacao.minimo=0.8` faz a rodada falhar abaixo de 80%, para quem quiser usar como trava antes
  de trocar de modelo.

**O que fica no CI** é a régua e o conjunto: `ConferenciaDaAvaliacaoTest` testa a conferência (acerto,
cada tipo de falha, acentos) e o próprio conjunto (30 ids únicos, origens que existem, e o saldo de
cada conta igual ao do lançamento mais novo). Um conjunto com um erro de digitação mediria o modelo
contra uma resposta errada.

## Alternativas consideradas

- **Rodar a avaliação no CI** com um modelo pago. Quebra o custo zero e manda os dados para fora.
- **Rodar no CI com o Ollama no processador.** 30 perguntas com um modelo de 4 bilhões de parâmetros
  levariam muitos minutos, com resultado que varia. Um CI que às vezes falha ensina a ignorar o
  vermelho.
- **Um modelo como juiz** (*LLM-as-a-judge*), dando nota a cada resposta. Útil para texto livre, mas
  aqui quase toda resposta tem um fato conferível (um valor, um artigo, uma recusa). A conferência por
  regra é exata e de graça.
- **Frameworks de avaliação** (promptfoo, DeepEval, Ragas). Ferramentas boas, em Node ou Python. Com
  30 perguntas, duas classes Java pequenas resolvem sem mais uma linguagem no caminho.

## Consequências

- **Positivas:** a qualidade do modelo fica medida, versionada e comparável, e o CI continua rápido e
  igual em toda rodada.
- **Negativas:** a avaliação depende de alguém rodar. Trocar de modelo sem rodar a avaliação não é
  barrado por nada automático. Trinta perguntas medem tendência, não garantem: uma resposta ruim pode
  aparecer numa pergunta que não está no conjunto. Um banco real teria centenas de perguntas, tiradas
  das conversas de verdade, e rodaria a avaliação a cada mudança.
