#!/usr/bin/env bash
# Cria um Pix EM_ANALISE de verdade, para a fila do backoffice (e para o teste E2E da devolução).
#
# Por que um script e não um Pix pela tela: um Pix só fica EM_ANALISE quando o estorno é recusado
# — a conta que pagou foi encerrada entre o débito e o estorno. Pela API, débito, crédito e estorno
# rodam na mesma requisição: não há como encerrar a conta "no meio" de forma determinística.
#
# O caminho, com o razão e o contas de verdade:
#   1. abre uma conta nova para a pessoa e deposita o valor;
#   2. o razão registra o ENVIO do Pix (o dinheiro sai da conta para a Liquidação Pix);
#   3. a conta, agora com saldo zero, é encerrada;
#   4. a transferência entra no banco do Pix já como EM_ANALISE (a Saga teria chegado aí depois
#      das tentativas de estorno recusadas).
# Na devolução, o Pix estorna o envio para outra conta ATIVA da mesma pessoa.
#
# Uso: ./scripts/pix-em-analise.sh [usuário-pagador] [usuário-recebedor] [valor]
#      (padrão: ana bruno 300.00). Imprime o id do Pix na última linha.

set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/token.sh

PAGADOR=${1:-ana}
RECEBEDOR=${2:-bruno}
VALOR=${3:-300.00}
CONTAS=${CONTAS_URL:-http://localhost:8081}
RAZAO=${RAZAO_URL:-http://localhost:8082}
ADMIN=${EMISSOR/\/realms\//\/admin\/realms\/}

api() {
  local metodo=$1 url=$2 corpo=${3:-} chave=${4:-}
  local args=(-s -X "$metodo" -w '\n%{http_code}' -H "Authorization: Bearer $(token)" -H 'Content-Type: application/json')
  [[ -n $corpo ]] && args+=(-d "$corpo")
  [[ -n $chave ]] && args+=(-H "Idempotency-Key: $chave")
  local resposta; resposta=$(curl "${args[@]}" "$url")
  CODIGO=${resposta##*$'\n'}
  CORPO=${resposta%$'\n'*}
}
exigir() { [[ " $* " == *" $CODIGO "* ]] || { echo "  ✘ esperado HTTP $*, veio $CODIGO: $CORPO" >&2; exit 1; }; }
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)" <<< "$CORPO"; }
esperar() {
  local descricao=$1; shift
  for _ in {1..100}; do "$@" && return; sleep 0.1; done
  echo "  ✘ tempo esgotado: $descricao" >&2; exit 1
}

cliente_de() {
  api GET "$ADMIN/users?username=$1&exact=true&briefRepresentation=false"; exigir 200
  json "(d[0].get('attributes') or {}).get('cliente_id',[''])[0]"
}

pagador=$(cliente_de "$PAGADOR")
recebedor=$(cliente_de "$RECEBEDOR")
[[ -n $pagador && -n $recebedor ]] || { echo "  ✘ rode antes ./scripts/semear.sh" >&2; exit 1; }

api GET "$CONTAS/v1/clientes/$recebedor/contas"; exigir 200
conta_recebedor=$(json "next(c['id'] for c in d if c['situacao']=='ATIVA')")
nome_recebedor=$(json "'$RECEBEDOR'.capitalize() + ' Exemplo'")
api GET "$CONTAS/v1/clientes/$pagador"; exigir 200
nome_pagador=$(json "d['nome']")

# 1. Conta nova da pessoa, com o valor do Pix.
api POST "$CONTAS/v1/contas" "{\"clienteId\":\"$pagador\"}"; exigir 201
conta=$(json "d['id']")
no_razao() { api GET "$RAZAO/v1/contas/$conta"; [[ $CODIGO == 200 ]]; }
esperar "o razão receber a conta" no_razao
api POST "$RAZAO/v1/depositos" "{\"contaId\":\"$conta\",\"valor\":\"$VALOR\",\"descricao\":\"depósito\"}" "deposito-$conta"; exigir 201

# 2. O envio do Pix, no razão.
pix=$(python3 -c 'import uuid; print(uuid.uuid4())')
api POST "$RAZAO/v1/pix/envios" "{\"contaId\":\"$conta\",\"valor\":\"$VALOR\",\"descricao\":\"Pix para $nome_recebedor\"}" "pix:$pix:envio"
exigir 201
envio=$(json "d['id']")

# 3. Encerrar a conta (saldo zero). O razão fica sabendo pelo evento ContaEncerrada.
api POST "$CONTAS/v1/contas/$conta/encerramento"; exigir 200
encerrada() { api GET "$RAZAO/v1/contas/$conta"; [[ $CORPO == *ENCERRADA* ]]; }
esperar "o razão ver a conta encerrada" encerrada

# 4. A transferência, como a Saga a deixaria depois dos estornos recusados.
docker compose -f infra/docker/compose.yaml exec -T postgres psql -q -v ON_ERROR_STOP=1 -U pix -d pix > /dev/null <<SQL
INSERT INTO transferencia_pix (id, chave_idempotencia, pagador_conta_id, pagador_nome, recebedor_conta_id,
    recebedor_nome, recebedor_chave, recebedor_tipo_chave, valor, descricao, situacao, motivo,
    lancamento_envio_id, tentativas, proxima_tentativa_em, criada_em)
VALUES ('$pix', 'pix-em-analise-$pix', '$conta', '$nome_pagador', '$conta_recebedor',
    '$nome_recebedor', '$RECEBEDOR@exemplo.test', 'EMAIL', $VALOR, 'Pix de demonstração', 'EM_ANALISE',
    'Crédito recusado e estorno impossível: a conta do pagador foi encerrada',
    '$envio', 5, now(), now());
SQL
printf '  \033[32m✔ Pix %s de R$ %s em análise (conta pagadora %s encerrada)\033[0m\n' "$pix" "$VALOR" "$conta" >&2
echo "$pix"
