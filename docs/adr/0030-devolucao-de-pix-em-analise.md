# ADR-0030: Devolução de Pix em análise pelo backoffice, com trilha de auditoria

- **Status:** Aceita
- **Data:** 2026-10-28

## Contexto

Na Saga do Pix (ADR-0015), quando o crédito no recebedor falha, o Pix é **estornado**: o dinheiro
volta da Liquidação Pix para a conta que pagou. Mas se essa conta foi **encerrada** entre o débito e
o estorno, o razão não aceita o lançamento (conta encerrada não movimenta). A Saga desiste depois
das tentativas e deixa o Pix em **EM_ANALISE**: o dinheiro está parado na Liquidação Pix, e não
havia saída — a ADR-0015 deixou isso para "o backoffice da Fase 4".

Devolver dinheiro é uma decisão de pessoa, e precisa ficar registrado quem decidiu, quando e por quê.

## Decisão

- **`POST /v1/transferencias/{id}/devolucao`** no pix, só para o papel `analista`, com
  `{contaDestinoId, justificativa}`.
- **Regras** (no domínio, `TransferenciaPix`): só um Pix **EM_ANALISE** pode ser devolvido (senão,
  409); a conta destino precisa ser do **mesmo titular** do pagador e estar **ATIVA** (o pix
  pergunta ao contas); a justificativa tem de 10 a 500 caracteres.
- **A Saga volta a trabalhar:** a transferência vai para **ESTORNANDO** com a `conta_devolucao_id`, e
  o estorno sai para essa conta, com uma chave de idempotência própria. O razão só aceita estornar
  para outra conta quando a pagadora está **ENCERRADA** — nenhum outro caso desvia dinheiro.
- **Auditoria:** cada decisão vira uma linha em `decisao_de_analise` (o `sub` e o nome da analista,
  tirados do token; a decisão; a conta; a justificativa; o momento), na mesma transação da mudança
  de situação. A tabela só recebe `INSERT`.
- **No backoffice:** a fila mostra os Pix em análise do mais antigo para o mais novo (com um índice
  parcial só para eles); a tela de decisão mostra o Pix, o motivo e as contas do titular — só as
  ATIVAS podem ser escolhidas.
- **Para testar de ponta a ponta**, `scripts/pix-em-analise.sh` cria um Pix em análise de verdade:
  envio real no razão, encerramento real da conta e a transferência gravada como a Saga a deixaria.
  Pela API não há como provocar isso de forma determinística: débito, crédito e estorno rodam na
  mesma requisição.

## Alternativas consideradas

- **Devolver sempre para a conta de origem** — ela está encerrada: é o motivo de o Pix estar parado.
- **Analista escolhe qualquer conta** — abriria o caminho mais simples para desviar dinheiro;
  ficar no mesmo titular segue o princípio do MED (o Mecanismo Especial de Devolução do Banco
  Central): o dinheiro volta para quem pagou.
- **Lançamento manual no razão** — sem passar pela Saga, a transferência ficaria "em análise" para
  sempre, e o extrato não saberia do estorno.
- **Auditoria só em log** — log some, é alterável e não se consulta com SQL. A tabela é parte do
  dado.

## Consequências

- **Positivas:** o dinheiro parado tem saída, com dono e motivo registrados; a regra que evita
  desvio está em duas camadas (pix: mesmo titular e ativa; razão: só para pagadora encerrada).
- **Negativas:** só existe a decisão "devolver" (o `CHECK` já deixa espaço para outras, como
  "recusar", quando a regra existir); não há aprovação em duas pessoas (o "princípio dos quatro
  olhos"), que um banco de verdade exigiria acima de um valor.
