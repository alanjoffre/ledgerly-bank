# Eventos e contratos entre serviços

Este documento é o **contrato** entre os serviços: o formato de cada evento publicado no Kafka e as
APIs que um serviço chama no outro. Quem produz não muda um campo sem atualizar este arquivo, e
uma mudança que quebra quem consome ganha um tópico novo (`.v2`), nunca um campo trocado em silêncio.

> **Evento** é um fato que já aconteceu, escrito no passado: `ContaAberta`, `LancamentoRegistrado`.
> Quem publica não sabe quem vai consumir, nem espera resposta — é isso que deixa os serviços
> independentes. **Comando** (pedido que espera resposta) continua sendo HTTP.

## Como um evento sai de um serviço: Outbox

Todo serviço que publica usa o **Outbox Pattern** ([ADR-0013](../adr/0013-outbox-para-publicar-eventos.md)):
o evento é gravado numa tabela `outbox` **na mesma transação** do dado que mudou, e um publicador
separado lê a tabela e manda ao Kafka. Ou os dois ficam gravados, ou nenhum — nunca "o dado mudou e
o evento se perdeu".

Consequência que todo consumidor precisa respeitar: a entrega é **pelo menos uma vez**
(*at-least-once*). Se o publicador cair depois de mandar e antes de marcar como publicado, o evento
vai de novo. **Todo consumidor é idempotente** — processar duas vezes dá o mesmo resultado que uma.

## O envelope

Toda mensagem tem o mesmo formato (JSON, UTF-8):

```json
{
  "eventoId": "3f1c9a52-7d1e-4b8a-9c0e-2a6b5f4d8e10",
  "tipo": "LancamentoRegistrado",
  "versao": 1,
  "ocorridoEm": "2026-10-27T18:30:00.123456Z",
  "dados": { }
}
```

| Campo | O que é |
|---|---|
| `eventoId` | Identificador único do evento — o mesmo nas repetições. Serve para rastrear; hoje nenhum consumidor descarta duplicata por ele: a idempotência vem da forma de gravar (`ON CONFLICT`, *upsert* por `_id`, "já está nessa situação"). |
| `tipo` | O nome do fato. Também vai no cabeçalho Kafka `tipo`, para filtrar sem ler o corpo. |
| `versao` | Versão do formato de `dados` dentro do tópico. Campo novo opcional não muda a versão. |
| `ocorridoEm` | Quando o fato aconteceu (UTC, ISO-8601, microssegundos). |
| `dados` | O conteúdo do evento (abaixo, por tipo). |

Regras de formato, as mesmas das APIs REST ([ADR-0012](../adr/0012-erros-problem-details-e-dinheiro-como-texto.md)):
dinheiro é **texto** (`"10.50"`), identificadores são UUID em texto, datas em ISO-8601 UTC.

**Chave da mensagem** (a *key* do Kafka): o identificador do agregado. Mensagens com a mesma chave
caem na mesma partição e chegam **na ordem em que foram publicadas** — é assim que os eventos de uma
mesma conta não se embaralham.

## Tópicos

Criados por [criar-topicos.sh](../../infra/docker/kafka/criar-topicos.sh) (a criação automática
está desligada). Três partições cada.

| Tópico | Dono (produz) | Quem consome | Chave | Retenção |
|---|---|---|---|---|
| `contas.eventos.v1` | contas | razao | `contaId` | 7 dias |
| `razao.lancamentos.v1` | razao | extrato | `lancamentoId` | **infinita** — o extrato se reconstrói daqui |
| `pix.transferencias.v1` | pix | extrato | `pixId` | 7 dias |
| `<tópico>.dlt` | o consumidor | pessoas (análise) | a original | 7 dias |

**DLT** (*dead letter topic*): a mensagem que o consumidor não consegue processar, nem depois das
novas tentativas, vai para o `.dlt` do tópico — em vez de travar a fila inteira atrás dela.

## `contas.eventos.v1`

Produzido pelo serviço **contas**. O razão consome e mantém a conta dele igual.

| `tipo` | `dados` | O que o razão faz |
|---|---|---|
| `ContaAberta` | `{"contaId", "clienteId", "moeda": "BRL"}` | Abre a conta (se já existe, nada) |
| `ContaBloqueada` | `{"contaId"}` | Situação `BLOQUEADA` |
| `ContaDesbloqueada` | `{"contaId"}` | Situação `ATIVA` |
| `ContaEncerrada` | `{"contaId"}` | Situação `ENCERRADA` (normalmente já está: o encerramento é síncrono) |

Encerramento é **definitivo**: qualquer evento que chegue para uma conta já encerrada no razão é
descartado (é um evento antigo, ultrapassado pelo encerramento).

Por que o encerramento continua síncrono: ele depende de uma resposta que só o razão tem — o saldo
é zero? Os outros três não perguntam nada ao razão; só avisam. Ver
[ADR-0014](../adr/0014-contas-avisa-o-razao-por-evento.md).

## `razao.lancamentos.v1`

Produzido pelo **razao**, um evento por lançamento. O extrato consome.

`tipo`: `LancamentoRegistrado`.

```json
{
  "lancamentoId": "9b2e…",
  "tipo": "PIX_ENVIO",
  "descricao": "Aluguel",
  "moeda": "BRL",
  "registradoEm": "2026-10-27T18:30:00.123456Z",
  "partidas": [
    { "sequencia": 1041, "contaId": "c1…", "contaInterna": false, "tipo": "DEBITO",  "valor": "1200.00", "saldoApos": "300.00" },
    { "sequencia": 1042, "contaId": "00000000-0000-0000-0000-000000000702", "contaInterna": true, "tipo": "CREDITO", "valor": "1200.00", "saldoApos": "1200.00" }
  ]
}
```

| Campo | O que é |
|---|---|
| `tipo` | `DEPOSITO`, `SAQUE`, `TRANSFERENCIA`, `PIX_ENVIO`, `PIX_RECEBIMENTO`, `PIX_ESTORNO` |
| `partidas[].sequencia` | Número da partida no razão: cresce sempre. Numa conta, a partida de maior sequência tem o saldo atual. |
| `partidas[].contaInterna` | `true` para as contas do próprio banco (Caixa, Liquidação Pix) — o extrato do cliente as ignora. Desde a Fase 3, o `contaId` de uma conta interna é uma das suas 16 sub-contas (no exemplo, a sub-conta 7 da Liquidação Pix, [ADR-0019](../adr/0019-contas-quentes-em-subcontas.md)): quem precisar da conta-mãe usa o último byte do id (`01` Caixa, `02` Liquidação Pix). |
| `partidas[].saldoApos` | Saldo da conta logo depois desta partida, calculado pelo razão (o extrato não recalcula nada). |

Os eventos de lançamentos diferentes podem chegar fora de ordem entre si (chaves diferentes,
partições diferentes). Por isso o saldo do extrato vale a partida de **maior sequência**, não a
última que chegou.

## `pix.transferencias.v1`

Produzido pelo **pix**, quando uma transferência termina. O extrato consome para escrever "Pix
enviado para Maria" em vez de só "PIX_ENVIO".

`PixConcluido`:

```json
{
  "pixId": "5d0a…",
  "valor": "1200.00",
  "moeda": "BRL",
  "descricao": "Aluguel",
  "pagador":   { "contaId": "c1…", "nome": "João da Silva" },
  "recebedor": { "contaId": "c2…", "nome": "Maria Souza", "tipoChave": "EMAIL", "chave": "m***@exemplo.com" },
  "lancamentoEnvioId": "9b2e…",
  "lancamentoRecebimentoId": "a7c4…",
  "concluidoEm": "2026-10-27T18:30:00.456789Z"
}
```

`PixEstornado`: os mesmos campos de `pagador`, `recebedor`, `valor`, `moeda`, `descricao` e
`lancamentoEnvioId`, mais:

| Campo | O que é |
|---|---|
| `lancamentoEstornoId` | O lançamento que devolveu o dinheiro ao pagador |
| `motivo` | Por que o crédito foi recusado, em frase para o cliente (por exemplo, `"a conta de destino está encerrada"`) |
| `estornadoEm` | Quando o estorno foi escriturado |
| `contaDevolucaoId` | Só na **devolução de um Pix em análise** (ADR-0030): a outra conta, do mesmo titular, para onde o dinheiro voltou porque a conta que pagou foi encerrada. Ausente no estorno comum, que volta para `pagador.contaId` |

A chave do recebedor vai **mascarada**: o extrato mostra, não precisa do valor completo.

Os dois tópicos que o extrato lê (`razao.lancamentos.v1` e `pix.transferencias.v1`) não têm ordem
entre si: o `PixConcluido` pode chegar antes do `LancamentoRegistrado` do mesmo dinheiro. O extrato
junta os dois pedaços no mesmo documento, em qualquer ordem.

## APIs internas novas (HTTP)

Chamadas de serviço para serviço. Na Fase 4 passam a exigir token de serviço.

### razao — movimentos do Pix

Três operações que passam pela conta interna **Liquidação Pix**
(`00000000-0000-0000-0000-000000000002`, natureza credora, dividida em 16 sub-contas desde a Fase 3):
o dinheiro de um Pix em andamento fica nela, entre sair do pagador e chegar ao recebedor.

Todas exigem `Idempotency-Key` e respondem como `POST /v1/transferencias` (201 novo · 200 repetido,
mesmos erros). O envio recebe `{"contaId", "valor", "descricao"?}`. O recebimento e o estorno
recebem também **`lancamentoEnvioId`** (Fase 3): o lançamento do envio de onde o dinheiro sai. O
razão confere se é mesmo um envio, se o valor é o mesmo, se o estorno volta para quem pagou e se o
envio ainda não teve saída; senão, `422 envio-pix-invalido`.

| Método e caminho | Débito | Crédito | `tipo` |
|---|---|---|---|
| `POST /v1/pix/envios` | conta do cliente | Liquidação Pix | `PIX_ENVIO` |
| `POST /v1/pix/recebimentos` | Liquidação Pix | conta do cliente | `PIX_RECEBIMENTO` |
| `POST /v1/pix/estornos` | Liquidação Pix | conta do cliente | `PIX_ESTORNO` |

### contas — titular e titularidade

| Método e caminho | O que faz | Resposta |
|---|---|---|
| `GET /v1/contas/{id}/titular` | Nome do titular e situação da conta, **sem o saldo** | `200 {"contaId","clienteId","nome","situacao"}` · `404` |
| `POST /v1/contas/{id}/titularidade` | `{"cpf"}` — o CPF é o do titular da conta? | `200 {"confere": true\|false}` · `404` conta inexistente |

O `titular` existe porque o `GET /v1/contas/{id}` pergunta o saldo ao razão: com o razão fora do ar,
ele responde 503 — e o Pix não poderia nem criar uma transferência para esperar o razão voltar.

O Pix precisa saber se a chave CPF pertence ao dono da conta, mas o CPF completo não sai do serviço
de contas: o Pix pergunta "é este?", e a resposta é sim ou não.

## Portas

| Serviço | Porta |
|---|---|
| contas | 8081 |
| razao | 8082 |
| pix | 8083 |
| extrato | 8084 |
