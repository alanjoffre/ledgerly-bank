# ADR-0043: O modelo de risco se confere com tolerância, não byte a byte

- **Status:** Aceita
- **Data:** 2026-10-30

## Contexto

A [ADR-0032](0032-antifraude-regras-e-modelo-onnx.md) diz que rodar o treino de novo dá o mesmo modelo,
"byte a byte", e o CI conferia isso. **Esse "byte a byte" estava errado.** A `main` ficou vermelha num
commit que só mudava o README: o CI treinou e achou `pesos.json` e `referencia.csv` diferentes dos
versionados, com o código e as dependências iguais. A mesma falha apareceu em dois PRs do Dependabot.

A ADR aceita não se edita: esta corrige aquela afirmação.

## O que foi medido

O treino usa `float32`, e a biblioteca de álgebra (OpenBLAS) escolhe o caminho de cálculo conforme as
instruções do processador. Forçando cada caminho com `OPENBLAS_CORETYPE`, na mesma máquina e com a
mesma semente:

| Núcleo forçado | Maior diferença nos pesos | Nas probabilidades |
| --- | --- | --- |
| Haswell, Zen (o padrão desta máquina) | 0 | 0 |
| Sandybridge, Nehalem, Excavator, Barcelona | 9,54e-07 | |
| Core2, Prescott | 9,06e-06 | 4,83e-06 |

A folga do CI era 1e-6, abaixo do que esse ruído alcança. **Na mesma máquina, o treino é sempre igual**;
entre processadores diferentes, os últimos dígitos mudam. As máquinas do GitHub Actions não são todas
iguais.

**O que não foi provado:** esta máquina (i7-13620H) não executa o caminho AVX-512, então a causa exata
no CI é uma hipótese forte, não uma medida. Por isso o job agora imprime o `lscpu` e a maior diferença,
para confirmar no log.

## Decisão

O treino não ficou mais rígido; a conferência ficou honesta. `ml/conferir.py` compara com tolerância:
**1e-4** nos pesos e nas probabilidades, **2e-4** nas métricas, acima do ruído medido e abaixo de
qualquer mudança real.

As métricas que vêm de contagem (retidos por mil, precisão e revocação) têm folga maior, de 0,2 e 0,04: um único
Pix cruzando o limiar pelo ruído já as muda além de 2e-4. Mudança real nessas métricas exige mudança nos pesos, e
os pesos seguem com 1e-4.

## Prova

Ruído de processador passa. Mudança real reprova: taxa de fraude de 2,1% no gerador e `C=0,5` no treino
foram recusados pelo conferidor.

## Alternativas descartadas

- **Fixar o núcleo com `OPENBLAS_CORETYPE` no CI:** daria o byte a byte só no CI, e não em quem
  reproduz o modelo na própria máquina; a promessa continuaria falsa.
- **Treinar com `float64`:** diminui o ruído, mas não elimina, e muda o modelo por um problema da conferência.
- **Tirar a conferência:** perderia a garantia de que o modelo versionado sai deste código e destes dados.

## Consequências

- O modelo versionado continua sendo o que serve em produção: os testes do antifraude usam os pesos versionados.
- Quem treinar em outro processador pode ver os últimos dígitos mudarem no `git diff`. Não é defeito, e
  não é para versionar: `ml/README.md` explica.
- Uma mudança real menor que a tolerância passaria. Nesse tamanho, ela não muda a decisão de nenhum Pix
  de forma mensurável.
