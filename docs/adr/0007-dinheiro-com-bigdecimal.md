# ADR-0007: Dinheiro com `BigDecimal` e arredondamento bancário

- **Status:** Aceita
- **Data:** 2026-10-26

## Contexto

`double` e `float` guardam números em base 2 e não representam 0,10 exatamente:
`0.1 + 0.2` dá `0.30000000000000004`. Num banco, isso vira centavo que some ou aparece.
Também é preciso decidir **quando** e **como** arredondar, e como dividir um valor sem perder
centavo (R$ 100,00 em 3 parcelas).

## Decisão

Um *Value Object* `Dinheiro` (no núcleo compartilhado) com:

- valor em **`BigDecimal`**, sempre na escala da moeda (BRL = 2 casas);
- valor com casas a mais é **recusado** na criação — nunca arredondado em silêncio;
- **arredondamento bancário** (`HALF_EVEN`, "meio para o par") só onde é inevitável
  (multiplicação: juros, tarifa percentual);
- **rateio** que distribui o resto centavo a centavo (algoritmo de alocação de Martin Fowler):
  R$ 100,00 / 3 = 33,34 + 33,33 + 33,33;
- operações entre moedas diferentes são proibidas (`MoedasDiferentesException`).

## Alternativas consideradas

- **`long` em centavos** — mais rápido e também exato; é usado em sistemas de alto volume.
  Descartado porque deixa a escala implícita (o `long` 1050 é R$ 10,50 ou ¥ 1.050?) e exige
  conversão em cada borda. Pode voltar numa ADR futura se a medição (Fase 3) mostrar gargalo.
- **Biblioteca pronta (Joda-Money, JSR 354/Moneta)** — resolveria, mas o tipo é pequeno e central
  ao domínio; implementá-lo mostra o raciocínio e evita uma dependência no núcleo.

## Consequências

- **Positivas:** nenhuma perda de centavo, comprovada por teste de propriedade (10 mil rateios
  aleatórios somam exatamente o original).
- **Negativas:** `BigDecimal` é mais lento e mais verboso que `long`; no banco de dados, a coluna é
  `NUMERIC(19,2)`, e o mapeamento precisa preservar a escala.
