# ADR-0012: Erros em Problem Details (RFC 9457) e dinheiro como texto no JSON

- **Status:** Aceita
- **Data:** 2026-10-26

## Contexto

Quem consome a API (o front-end, outro serviço) precisa de duas garantias: saber **que erro** houve
sem interpretar texto, e ler valores em dinheiro **sem perder centavo**.

## Decisão

**Erros** seguem o padrão **Problem Details** (RFC 9457), o formato oficial da web para erro de API:

```json
{ "type": "urn:ledgerly:erro:saldo-insuficiente",
  "title": "Regra de negócio violada",
  "status": 422,
  "detail": "Saldo insuficiente na conta ...: saldo BRL 10.00, débito de BRL 50.00",
  "instance": "/v1/saques" }
```

- o `type` é **estável** — é por ele que o cliente decide o que fazer; o `detail` é para gente ler
  e pode mudar;
- erros de validação trazem `campos`, com o motivo de cada campo;
- até os erros do próprio Spring (JSON malformado, cabeçalho faltando) saem em português e nesse
  formato (`mensagens.properties`);
- códigos HTTP: `400` dado mal formado; `404` não existe; `409` conflito (CPF repetido, trava
  otimista); `422` regra de negócio; `503` dependência fora do ar.

**Dinheiro** trafega como **texto**: `"valor": "10.50"`. Em JavaScript, todo número é ponto
flutuante; um front-end que lesse `10.50` como número poderia acumular erro.

## Alternativas consideradas

- **Formato de erro próprio** — funciona, mas cada cliente aprende um formato novo; o RFC já é
  suportado por bibliotecas.
- **Dinheiro como número no JSON** — comum, e o Java leria exato com `BigDecimal`, mas transfere o
  risco para o front-end.
- **Dinheiro em centavos inteiros** (`1050`) — exato, mas exige saber a escala de cada moeda.

## Consequências

- **Positivas:** contrato de erro previsível e documentável; valores exatos de ponta a ponta.
- **Negativas:** quem consome precisa converter o texto do valor (no front, com uma biblioteca
  decimal). O `type` em URN (`urn:ledgerly:erro:...`) não é um link clicável; a lista de tipos fica
  no README de cada serviço.
