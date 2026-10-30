# ADR-0035: A explicação do Pix retido: o modelo de linguagem escreve, uma trava confere os números, e há um texto fixo de reserva

- **Status:** Aceita
- **Data:** 2026-10-29

## Contexto

A analista que abre um Pix retido vê a nota e os motivos (ADR-0032), em frases curtas e separadas.
Ajudaria ter um parágrafo que juntasse tudo, como uma colega experiente explicaria: "o valor é 15
vezes o normal deste cliente, de madrugada, para uma conta aberta ontem: o desenho de um golpe".

Um modelo de linguagem escreve isso bem. O problema é que ele **inventa com a mesma confiança**: um
"15 vezes a média" que na verdade é 1,5 muda a decisão da analista. E ele pode estar fora do ar, ou
levar segundos.

## Decisão

**O modelo de linguagem só explica; não decide.** A nota e a decisão já estão tomadas pelas regras e
pelo modelo de ML antes de qualquer texto existir. O texto não muda nada no Pix.

**O pedido leva só o que a analista já vê**: a decisão, a nota, os seis fatos e os motivos. Nenhum
id de conta e nenhum nome: o modelo não precisa deles para explicar, e o que não vai não vaza. As
instruções pedem de 2 a 3 frases em português, sem inventar número, e sem dizer se a analista deve
liberar ou recusar. Modelo `gemma3:4b`, temperatura 0,2, até 220 *tokens* (pedaços de palavra), 30 s
de tempo limite.

**A trava de números** ([`GuardrailDeNumeros`](../../servicos/antifraude/src/main/java/io/github/alanjoffre/ledgerly/antifraude/dominio/GuardrailDeNumeros.java)):
**todo número do texto tem de existir na avaliação**: a nota, os fatos ou os motivos. Um número que
não veio de lá derruba o texto inteiro. Os números têm de estar no formato brasileiro (`4.800,00`,
`15,0`). Um "1.5" em inglês é descartado, porque lido como 15 poderia bater por acaso com outro fato.
No mercado, isso é um *guardrail* de saída: uma regra determinística que confere o que o modelo
escreveu antes de alguém ler.

**O texto fixo de reserva** ([`Explicacao.textoFixo`](../../servicos/antifraude/src/main/java/io/github/alanjoffre/ledgerly/antifraude/dominio/Explicacao.java)):
"Nota 640 de 1000: o Pix foi retido para uma pessoa analisar. Pesou: …", montado com os motivos, sem
modelo. É o que vale quando:

- o texto inventou número: o texto fixo é **gravado** no lugar, e o modelo não é chamado de novo para
  aquele Pix;
- o modelo está fora do ar ou lento: o texto fixo vai na resposta, mas **não** é gravado, e a próxima
  leitura tenta de novo. Medido em 30/10/2026: com o modelo já na placa de vídeo, o texto leva cerca de
  2,5 s; na carga a frio (o `gemma3` subindo para a placa no lugar do modelo do assistente), a
  primeira leitura passou dos 30 s e voltou o texto fixo, e a segunda trouxe o texto do modelo. É o
  comportamento esperado: a analista nunca espera mais que o tempo limite;
- o Pix foi aprovado: ninguém precisa ler por que um Pix normal seguiu, e o modelo nem é chamado.

**Uma vez por Pix.** A explicação é escrita na primeira leitura (`GET
/v1/avaliacoes/{id}/explicacao`, só a analista e os serviços) e gravada com a origem (modelo ou
texto fixo) e o nome do modelo. A analista que abrir o Pix depois lê o mesmo texto. Não há transação
em volta da chamada ao modelo: ela leva segundos, e uma conexão do banco presa esse tempo faria falta
para o Pix.

**No backoffice**, a tela do Pix retido mostra "Escrevendo a explicação…" enquanto espera e, se a
explicação falhar, mostra o resto da tela sem ela: a nota e os motivos continuam ali.

## Alternativas consideradas

- **Sem modelo de linguagem**, só o texto fixo. Funciona, e é a reserva. Mas a fase é de IA aplicada,
  e a explicação é o caso mais seguro para usá-la: só lê, não decide, e uma pessoa confere.
- **Pedir ao modelo que "não invente"** e confiar. As instruções pedem isso, e um modelo de 4
  bilhões de parâmetros às vezes desobedece. A trava não depende de obediência.
- **Gerar a explicação na hora do Pix.** Deixaria o Pix esperando segundos por um texto que ninguém
  vai ler na maioria dos casos. Na primeira leitura, só o Pix retido paga.
- **Uma trava que confere cada número no lugar certo** (a idade da conta é mesmo 1 dia?). Exigiria
  entender a frase, e quem entende frase é outro modelo, que também erra.

## Consequências

- **Positivas:** a analista ganha um texto corrido sem risco de número inventado, e o backoffice
  funciona igual sem o Ollama.
- **Negativas, e o limite que fica escrito:** a trava confere que o número **existe** na avaliação,
  não que está **no lugar certo**. "A conta tem 15 dias" passa se 15 for a razão da média. Ela pega o
  número inventado, que é o erro comum de um modelo de linguagem; a troca de um fato por outro, não.
  Por isso a explicação nunca aparece sozinha: a nota e os motivos de verdade ficam na mesma tela.
