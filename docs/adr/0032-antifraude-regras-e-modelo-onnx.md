# ADR-0032: Antifraude com regras explícitas e um modelo treinado em Python, executado na JVM (ONNX)

- **Status:** Aceita
- **Data:** 2026-10-29

## Contexto

Até a Fase 4, todo Pix com saldo saía. Um banco de verdade pergunta antes: **este Pix parece
golpe?** A pergunta tem três exigências que puxam para lados diferentes:

- **rápida**: está no caminho do clique do cliente, antes do débito. A resposta tem de vir em
  milissegundos, não em segundos;
- **explicável**: a analista que olha um Pix segurado precisa saber *por quê*. "O modelo disse 0,77"
  não ajuda ninguém a decidir;
- **aprendida dos dados**: "valor 15 vezes acima da média do pagador, de madrugada, para uma conta
  aberta ontem" pesa mais do que cada sinal sozinho. Regra escrita à mão não acha essas combinações.

O padrão de mercado é juntar as duas coisas: **regras** para o que o banco já sabe que é golpe, e um
**modelo estatístico** para o resto.

## Decisão

Um serviço novo, **`servicos/antifraude`** (Quarkus 3.40.1, porta 8085, banco próprio `antifraude`),
com `POST /v1/avaliacoes`. Só o serviço de Pix chama, com o token de serviço (ADR-0027).

**O modelo** é treinado em Python ([`ml/treinar.py`](../../ml/treinar.py)) e exportado em **ONNX**
(*Open Neural Network Exchange*, um formato aberto de modelo que qualquer linguagem executa). A JVM
roda o arquivo com o **ONNX Runtime** (`com.microsoft.onnxruntime:onnxruntime` 1.31.1), dentro do
próprio serviço:

- **dados sintéticos** de Pix com fraude rotulada, gerados com semente fixa: 60 mil Pix, 2% de fraude.
  Rodar de novo dá o mesmo modelo, byte a byte. Nenhum dado de pessoa real;
- **seis sinais**: o valor, a razão sobre a média do pagador, os Pix na última hora, se o recebedor é
  novo, se é madrugada (Brasília) e a idade da conta que recebe;
- **dois candidatos**, escolhidos pela métrica: a regressão logística e as árvores com gradiente. Com
  2% de fraude, a métrica é a **precisão média** (área sob a curva de precisão × revocação), não a
  acurácia, que daria 98% a um modelo que nunca acusa ninguém. No conjunto de teste (15 mil Pix):

  | Candidato | Precisão média | AUC-ROC |
  | --- | --- | --- |
  | **regressão logística (escolhida)** | **0,553** | 0,940 |
  | árvores com gradiente | 0,517 | 0,952 |

  A regressão logística ganhou na métrica que importa, e tem uma vantagem a mais: é uma soma. A
  parte de cada sinal na nota é `peso × (x − média) / escala`, e é isso que vira o **motivo** que a
  analista lê ("15,0 vezes a média dos Pix anteriores do pagador"). O treino grava esses pesos em
  `pesos.json`, junto do `.onnx`;
- **teste de paridade**: o treino grava 50 entradas e a probabilidade que o Python calculou
  (`referencia.csv`). O teste do Java confere que a JVM dá o mesmo número. Se alguém mexer no treino
  e esquecer de trocar o arquivo, o build falha.

**As regras** ([`PoliticaDeRisco`](../../servicos/antifraude/src/main/java/io/github/alanjoffre/ledgerly/antifraude/dominio/PoliticaDeRisco.java)):
rajada (5 Pix ou mais na hora anterior) e "conta laranja" (R$ 1.000 ou mais para uma conta aberta há
menos de 2 dias, que nunca recebeu deste pagador). Regra só **sobe** a nota, até "segurar"; regra
nenhuma recusa sozinha.

**A nota e a decisão:** nota 0–1000 = probabilidade × 1000, e os limites saíram da tabela de
limiares do treino (`ml/metricas.json`):

- **segurar a partir de 200**: retém cerca de 2% dos Pix e pega 56% das fraudes; metade dos retidos
  é fraude de verdade;
- **recusar a partir de 900**: nenhum alarme falso no teste, mas pega só 10% das fraudes. O resto
  vai para uma pessoa.

**O histórico do pagador** (média, Pix na última hora, recebedores conhecidos) vem das próprias
avaliações gravadas no banco do antifraude. Ele não lê o banco de ninguém. A avaliação é
**idempotente** pela transferência: a Saga repete o pedido quando não tem resposta, e a segunda
pergunta recebe a mesma avaliação, sem contar a primeira no histórico.

**Medido** (100 mil Pix, 3 rodadas, com a máquina sob carga de outro projeto): o modelo na JVM leva
**p50 ≈ 6,5 µs** e **p99 ≈ 17–24 µs** por Pix. O custo do antifraude no Pix é a ida e volta HTTP e o
banco, não o modelo.

## Alternativas consideradas

- **Só regras.** Explicável e simples, mas não acha combinação de sinais, e cada golpe novo vira uma
  regra a mais. Ficaram as regras para o que é certeza; o resto é do modelo.
- **Treinar em Java** (Tribuo, Smile). Funciona, mas o mercado treina em Python, com scikit-learn, e
  quem lê o portfólio espera ver esse caminho: o cientista de dados treina, a engenharia executa. O
  ONNX é o contrato entre os dois.
- **Servir o modelo num serviço Python à parte** (FastAPI). Mais um serviço, mais uma ida e volta de
  rede no caminho do Pix, para uma conta de microssegundos. O modelo cabe dentro da JVM (544 bytes).
- **Árvores com gradiente.** AUC um pouco maior, precisão média menor, e o "porquê" de cada Pix
  exigiria SHAP (um método para dividir a nota entre os sinais), mais uma biblioteca e mais tempo por
  Pix.

## Consequências

- **Positivas:** decisão em microssegundos, explicável sinal a sinal, com o treino reproduzível e a
  paridade Python × Java travada no build.
- **Negativas:** os dados são sintéticos: os números medem o modelo contra o gerador, não contra
  golpe de verdade. Um banco real treinaria com o histórico rotulado e reavaliaria o modelo de tempos
  em tempos (o *drift*: o golpe muda, o modelo envelhece). Isso fica fora do escopo.
- **Python no repositório**, fora do build Maven: quem só compila o Java não precisa dele. O `.onnx`
  vai versionado.
