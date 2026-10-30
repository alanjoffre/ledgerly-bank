#!/usr/bin/env bash
# Cria um Pix RETIDO pelo antifraude de verdade, para a fila "Pix retidos" do backoffice (e para o
# teste E2E da liberação e da recusa).
#
# Diferente do pix-em-analise.sh, aqui não há atalho no banco: o Pix passa pela API, e quem retém
# é o antifraude, pela regra da conta "laranja" (ADR-0032) — conta recebedora aberta há menos de
# 2 dias, que nunca recebeu deste pagador, e valor a partir de R$ 1.000,00. A regra põe a nota em
# 500: SEGURAR.
# De madrugada o modelo soma o sinal do horário e a nota sobe (882 medido às 04:32 com R$ 1.500,00).
# A partir de 900 o antifraude recusa sozinho: por isso o padrão é R$ 1.500,00, e não mais.
#
# O caminho, com o razão, o contas, o antifraude e o Pix de verdade:
#   1. abre uma conta nova para o pagador e deposita o valor;
#   2. abre uma conta nova para o recebedor (a "laranja": aberta agora) e registra uma chave nela;
#   3. o Pix sai pela API, com token de serviço; o Pix pergunta ao antifraude antes do débito.
#
# Uso: ./scripts/pix-retido.sh [usuário-pagador] [usuário-recebedor] [valor]
#      (padrão: ana bruno 1500.00). Imprime o id do Pix na última linha.

set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/token.sh

PAGADOR=${1:-ana}
RECEBEDOR=${2:-bruno}
VALOR=${3:-1500.00}
CONTAS=${CONTAS_URL:-http://localhost:8081}
RAZAO=${RAZAO_URL:-http://localhost:8082}
PIX=${PIX_URL:-http://localhost:8083}
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

conta_nova() {
  api POST "$CONTAS/v1/contas" "{\"clienteId\":\"$1\"}"; exigir 201
  local conta; conta=$(json "d['id']")
  no_razao() { api GET "$RAZAO/v1/contas/$conta"; [[ $CODIGO == 200 ]]; }
  esperar "o razão receber a conta" no_razao
  echo "$conta"
}

pagador=$(cliente_de "$PAGADOR")
recebedor=$(cliente_de "$RECEBEDOR")
[[ -n $pagador && -n $recebedor ]] || { echo "  ✘ rode antes ./scripts/semear.sh" >&2; exit 1; }

# 1. Conta nova do pagador, com o valor do Pix.
conta=$(conta_nova "$pagador")
api POST "$RAZAO/v1/depositos" "{\"contaId\":\"$conta\",\"valor\":\"$VALOR\",\"descricao\":\"depósito\"}" "deposito-$conta"; exigir 201

# 2. A conta "laranja" do recebedor, aberta agora, com uma chave nova.
laranja=$(conta_nova "$recebedor")
chave="$RECEBEDOR.${laranja:0:8}@exemplo.test"
api POST "$PIX/v1/chaves" "{\"contaId\":\"$laranja\",\"tipo\":\"EMAIL\",\"valor\":\"$chave\"}"; exigir 201

# 3. O Pix. 202 = a resposta ainda não é a definitiva: está RETIDO, esperando uma analista.
api POST "$PIX/v1/transferencias" \
  "{\"pagadorContaId\":\"$conta\",\"chave\":\"$chave\",\"valor\":\"$VALOR\",\"descricao\":\"Pix de demonstração\"}" \
  "pix-retido-$conta"
exigir 202
[[ $(json "d['situacao']") == RETIDA ]] || { echo "  ✘ o Pix não ficou retido: $CORPO" >&2; exit 1; }
pix=$(json "d['id']")
printf '  \033[32m✔ Pix %s de R$ %s retido pelo antifraude (nota %s): %s\033[0m\n' \
  "$pix" "$VALOR" "$(json "d['notaDeRisco']")" "$(json "d['motivo']")" >&2
echo "$pix"
