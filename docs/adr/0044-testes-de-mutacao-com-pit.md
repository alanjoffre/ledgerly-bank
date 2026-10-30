# ADR-0044: Testes de mutação com PIT no domínio, fora do verify

- **Status:** Aceita
- **Data:** 2026-10-30

## Contexto

Cobertura diz quais linhas rodaram, não se algum teste repararia se elas estivessem erradas. Um banco
precisa saber a segunda coisa, no mínimo onde mora a regra de dinheiro.

**Teste de mutação** responde: a ferramenta altera o código de propósito (troca `>` por `>=`, devolve 0)
e roda os testes. Cada alteração é um **mutante**. Se um teste reprova, o mutante morreu; se nada reprova,
sobreviveu, e existe um comportamento que nenhum teste protege.

## Decisão

- **PIT** 1.30.1, no perfil Maven `mutacao`, com o plugin para JUnit 5.
- **Só `dominio` e `nucleo`:** a regra de negócio, que roda em milissegundos. Adaptadores dependem de
  banco e Kafka, e seriam horas de contêiner para pouca informação.
- **Limite de 90% por módulo**, e não só na média: um módulo fraco não se esconde atrás de outro.
- **Classes `*Exception` excluídas:** só carregam a mensagem e o código do erro, e quem confere isso são
  os testes de integração, que o perfil não roda.
- **Fora do `verify`:** roda no [`qualidade.yml`](../../.github/workflows/qualidade.yml), toda segunda e sob demanda.

## Resultado

**541 mutantes, 523 mortos: 96,7%.**

| Módulo | Mortos | Antes dos reforços |
| --- | --- | --- |
| núcleo | 38/38 (100%) | não medido |
| antifraude | 78/78 (100%) | 88% |
| assistente | 34/34 (100%) | 91% |
| extrato | 15/15 (100%) | |
| razão | 104/105 (99,0%) | 88% |
| contas | 60/62 (96,8%) | 71% |
| Pix | 194/209 (92,8%) | 84% |

As duas colunas não são a mesma régua: antes, as exceções ainda contavam e o núcleo não entrava.

## O que os mutantes acharam

**O teste do CPF chamava a função que testava.** O gerador de CPF dos testes usava o próprio `Cpf` para
calcular o dígito verificador. Se o cálculo estivesse errado, o gerador erraria igual, e o teste passaria:
8 mutantes sobreviviam na conta do CPF, inclusive um que faz o dígito **sempre dar 0**. A cobertura dessas
linhas era de 100%. O gerador agora calcula por uma fórmula independente, e há vetores conhecidos.

**`Dinheiro.ehMaiorQue` só era testado no caminho verdadeiro**, e vários limites não tinham teste na
fronteira exata: a descrição do Pix (140 caracteres passa, 141 não), a chave de idempotência (100 e 101), a
justificativa da análise, o peso de 0,25 no motivo do modelo, os extremos da nota de risco. Agora têm.

## O que sobrou

Os 18 sobreviventes, sem maquiagem:

- **Equivalentes** (a mudança não altera o comportamento, e nenhum teste poderia notar): `Lancamento.valor`
  (débitos = créditos, então somar um ou outro dá igual) e `NovasTentativas`, linhas 27 e 31. `BrCode.ler`,
  linhas 91 e 103, parecem equivalentes pela análise.
- **Não reforçados:** `BrCode`, linhas 133, 143, 153 e 179, limites do *parse* do QR Code.
- **Cobertos só pela integração**, que o perfil não roda: acessores de `Conta`, `TransferenciaPix` e `ChavePix`.

## Alternativas descartadas

- **Rodar no `verify`:** soma minutos a cada build, e o retorno é de análise, não de bloqueio a cada commit.
- **100% obrigatório:** obrigaria a escrever teste para mutante equivalente, o que é impossível, ou a excluir à mão.
- **Mutar os adaptadores:** caro, e quem protege aquilo são os testes de integração e os contratos.

## Consequências

- Só o domínio tem essa garantia: um erro num adaptador não aparece aqui.
- Mutação é medida, não meta: um número alto não diz que falta nenhum teste, só que os testes existentes reagem.
