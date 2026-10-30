# O treino do modelo de risco do Pix (Python)

O antifraude ([servicos/antifraude](../servicos/antifraude/README.md)) dá uma nota de risco a cada
Pix com um modelo estatístico. O modelo é **treinado aqui, em Python**, e exportado em **ONNX**
(*Open Neural Network Exchange*, um formato aberto que qualquer linguagem executa). A JVM só executa
o arquivo: o cientista de dados treina, a engenharia executa, e o ONNX é o contrato entre os dois
([ADR-0032](../docs/adr/0032-antifraude-regras-e-modelo-onnx.md)).

Esta pasta fica **fora do build Maven**: quem só compila o Java não precisa de Python. O modelo
treinado vai versionado em `servicos/antifraude/src/main/resources/modelo/`.

## Rodar

```bash
python3 -m venv ml/.venv && ml/.venv/bin/pip install -r ml/requisitos.txt
ml/.venv/bin/python ml/treinar.py
```

As versões estão fixas em [requisitos.txt](requisitos.txt) (scikit-learn 1.9.1, skl2onnx 1.20.0,
onnxruntime 1.31.1). Com a semente fixa, **rodar de novo dá o mesmo modelo, byte a byte**: o
`git diff` fica vazio (cerca de 10 s numa máquina comum).

O CI (job `ml`) treina de novo a cada envio e confere que as métricas são as mesmas e que os pesos
e as probabilidades de referência não mudaram além de 0,000001. A tolerância existe porque outro
processador pode mudar o último dígito de um número de ponto flutuante. Se mudar mais que isso,
alguém mexeu no treino e não copiou o modelo, e o CI fica vermelho.

## Os dados: sintéticos

Nenhum dado de pessoa real. [treinar.py](treinar.py) gera 60 mil Pix, 2% de fraude (o golpe que
manda o dinheiro para uma conta "laranja"), com a semente `20261029`. A sobreposição é de propósito:
cada sinal de fraude aparece **só às vezes** (uma fraude pode ter um sinal, ou nenhum), e há Pix
legítimo com cara de fraude (a compra grande, a conta que um amigo acabou de abrir). Sem isso, o
modelo acertaria tudo, e os números não diriam nada.

Os seis sinais, na ordem que o Java lê (o enum `Sinal`):

| # | Sinal | Conta |
|---|---|---|
| 0 | valor | log10(1 + valor em reais) |
| 1 | fora do costume | log10(valor ÷ média dos Pix anteriores do pagador); 0 sem histórico |
| 2 | Pix na última hora | quantos Pix o pagador fez na hora anterior |
| 3 | recebedor novo | 1 se o pagador nunca mandou Pix para essa conta |
| 4 | madrugada | 1 entre 0h e 5h59, no horário de Brasília |
| 5 | conta recebedora nova | log10(1 + idade da conta que recebe, em dias) |

## A escolha do modelo

Dois candidatos, treinados com 45 mil Pix e medidos nos outros 15 mil:

| Candidato | Precisão média | AUC-ROC |
|---|---|---|
| **regressão logística (escolhida)** | **0,553** | 0,940 |
| árvores com gradiente | 0,517 | 0,952 |

Com 2% de fraude, a **acurácia** engana: um modelo que nunca acusa ninguém acerta 98%. A **AUC-ROC**
também fica alta até para um modelo fraco quando quase tudo é normal. A métrica que manda é a
**precisão média**: a área sob a curva de precisão (dos que o modelo acusou, quantos eram fraude) ×
revocação (das fraudes, quantas ele pegou). A regressão logística ganhou nela, e ainda é uma soma: a
parte de cada sinal na nota é o motivo que a analista lê.

## Os limites da decisão

A nota do antifraude é a probabilidade × 1000. A tabela de limiares do modelo escolhido, no
conjunto de teste ([metricas.json](metricas.json)):

| Limiar | Nota | Retidos por mil Pix | Precisão | Revocação |
|---|---|---|---|---|
| 0,1 | 100 | 37,6 | 0,34 | 0,69 |
| **0,2** | **200: segurar** | **20,4** | **0,51** | **0,56** |
| 0,3 | 300 | 14,3 | 0,62 | 0,48 |
| 0,5 | 500 | 8,1 | 0,77 | 0,34 |
| 0,7 | 700 | 4,9 | 0,84 | 0,22 |
| **0,9** | **900: recusar** | **1,9** | **1,00** | **0,10** |

**Segurar a partir de 200**: cerca de 2% dos Pix vão para uma pessoa, metade deles é fraude de
verdade, e mais da metade das fraudes é pega. **Recusar a partir de 900**: nenhum alarme falso no
teste. Recusar sem pessoa só quando o modelo tem certeza.

## O que o treino grava

| Arquivo | O que é |
|---|---|
| `servicos/antifraude/src/main/resources/modelo/risco-pix.onnx` | o modelo que a JVM executa (544 bytes) |
| `servicos/antifraude/src/main/resources/modelo/pesos.json` | média, escala e peso de cada sinal: com eles, o Java diz quanto cada sinal pesou na nota |
| `servicos/antifraude/src/test/resources/modelo/referencia.csv` | 50 entradas (20 fraudes e 30 normais) e a probabilidade que o Python calculou |
| `ml/metricas.json` | os números acima |

O **teste de paridade** do Java (`ModeloOnnxTest`) roda as 50 linhas de `referencia.csv` na JVM e
confere que dá a mesma probabilidade. Se alguém treinar de novo e esquecer de copiar um dos arquivos,
o build falha.

## O que fica de fora

Os números medem o modelo contra o gerador, não contra golpe de verdade. Um banco real treinaria com
o histórico rotulado (os Pix que a analista confirmou como golpe), separaria treino e teste por data,
e acompanharia o *drift*: o golpe muda, e o modelo envelhece.
