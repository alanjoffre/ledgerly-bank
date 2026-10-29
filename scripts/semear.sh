#!/usr/bin/env bash
# Semeadura: prepara o banco para usar as telas (internet banking e backoffice) e os testes E2E.
#
# Para ana e bruno (usuários do realm, papel cliente): cadastra a pessoa no serviço contas (CPF
# gerado na hora, que não é de ninguém), abre a conta, deposita R$ 1.000,00, grava o cliente_id
# no usuário do Keycloak (é o que liga o login à conta, ADR-0026) e registra a chave Pix de e-mail.
#
# Pode rodar de novo: quem já tem cliente_id e conta é mantido como está.
#
# Pré-requisito: infraestrutura (com o Keycloak) e os serviços no ar — ver docs/guias/demo-fase-4.md.
# Uso: ./scripts/semear.sh

set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/token.sh

CONTAS=${CONTAS_URL:-http://localhost:8081}
RAZAO=${RAZAO_URL:-http://localhost:8082}
PIX=${PIX_URL:-http://localhost:8083}
ADMIN=${EMISSOR/\/realms\//\/admin\/realms\/}

ok() { printf '  \033[32m✔ %s\033[0m\n' "$*"; }

# Chamada com o token de serviço. Uso: api MÉTODO URL [JSON] [CHAVE] → corpo em $CORPO, status em $CODIGO
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

gerar_cpf() {
  local d=() i soma resto
  for i in {0..8}; do d+=($((RANDOM % 10))); done
  for tamanho in 9 10; do
    soma=0
    for ((i = 0; i < tamanho; i++)); do soma=$((soma + d[i] * (tamanho + 1 - i))); done
    resto=$((11 - soma % 11)); ((resto >= 10)) && resto=0
    d+=("$resto")
  done
  printf '%s' "${d[@]}"
}

# Grava o cliente_id no usuário do Keycloak. O PUT leva a representação inteira (lida antes):
# com o "user profile", uma representação parcial pode apagar atributos que não vieram.
vincular() {
  local usuario=$1 cliente=$2 id
  api GET "$ADMIN/users?username=$usuario&exact=true"; exigir 200
  id=$(json "d[0]['id']")
  api GET "$ADMIN/users/$id"; exigir 200
  local novo
  novo=$(python3 -c "
import json,sys
u=json.load(sys.stdin); u.setdefault('attributes',{})['cliente_id']=['$cliente']
print(json.dumps(u))" <<< "$CORPO")
  api PUT "$ADMIN/users/$id" "$novo"; exigir 204
}

cliente_do_keycloak() {
  local usuario=$1
  api GET "$ADMIN/users?username=$usuario&exact=true&briefRepresentation=false"; exigir 200
  json "(d[0].get('attributes') or {}).get('cliente_id',[''])[0]"
}

esperar_no_razao() {
  for _ in {1..100}; do
    api GET "$RAZAO/v1/contas/$1"; [[ $CODIGO == 200 ]] && return
    sleep 0.1
  done
  echo "  ✘ o razão não recebeu a conta $1" >&2; exit 1
}

semear() {
  local usuario=$1 nome=$2 chave=$3 cliente conta
  cliente=$(cliente_do_keycloak "$usuario")
  if [[ -n $cliente ]]; then
    api GET "$CONTAS/v1/clientes/$cliente"
    [[ $CODIGO == 200 ]] || cliente=""
  fi
  if [[ -z $cliente ]]; then
    api POST "$CONTAS/v1/clientes" "{\"nome\":\"$nome\",\"cpf\":\"$(gerar_cpf)\"}"; exigir 201
    cliente=$(json "d['id']")
    vincular "$usuario" "$cliente"
    ok "$usuario: cliente $cliente cadastrado e vinculado ao login"
  else
    ok "$usuario: já vinculado ao cliente $cliente"
  fi

  api GET "$CONTAS/v1/clientes/$cliente/contas"; exigir 200
  conta=$(json "next((c['id'] for c in d if c['situacao']=='ATIVA'), '')")
  if [[ -z $conta ]]; then
    api POST "$CONTAS/v1/contas" "{\"clienteId\":\"$cliente\"}"; exigir 201
    conta=$(json "d['id']")
    esperar_no_razao "$conta"
    api POST "$RAZAO/v1/depositos" "{\"contaId\":\"$conta\",\"valor\":\"1000.00\",\"descricao\":\"depósito inicial\"}" "semeadura-$conta"
    exigir 200 201
    ok "$usuario: conta $conta aberta com R\$ 1.000,00"
  else
    ok "$usuario: conta $conta já existe"
  fi

  api POST "$PIX/v1/chaves" "{\"contaId\":\"$conta\",\"tipo\":\"EMAIL\",\"valor\":\"$chave\"}"
  case $CODIGO in
    201) ok "$usuario: chave Pix $chave registrada" ;;
    409) ok "$usuario: chave Pix $chave já registrada" ;;
    *) exigir 201 ;;
  esac
}

printf '\n\033[1;34m▶ Semeadura do Ledgerly Bank\033[0m\n'
semear ana "Ana Exemplo" ana@exemplo.test
semear bruno "Bruno Exemplo" bruno@exemplo.test
printf '\nPronto. Internet banking: http://localhost:8080 (ana / ana-local)  ·  backoffice: http://localhost:8090 (carla / carla-local)\n'
