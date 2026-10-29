#!/usr/bin/env bash
# Demonstração da Fase 1: percorre o banco pela API, como um cliente faria.
#
# Pré-requisito: infraestrutura e os dois serviços rodando (ver docs/guias/demo-fase-1.md).
# Uso: ./scripts/demo-fase-1.sh
# Só precisa de bash e curl. Todos os dados são fictícios; os CPFs são gerados na hora.

set -euo pipefail
cd "$(dirname "$0")/.."

# Desde a Fase 4 os serviços exigem token: todo curl daqui leva o token de serviço (ADR-0027).
source scripts/lib/token.sh
usar_token_no_curl

CONTAS=${CONTAS_URL:-http://localhost:8081}
RAZAO=${RAZAO_URL:-http://localhost:8082}

passo() { printf '\n\033[1;34m▶ %s\033[0m\n' "$*"; }
ok()    { printf '  \033[32m✔ %s\033[0m\n' "$*"; }

# Faz a chamada e imprime "HTTP <código>" + o corpo. Uso: chamar MÉTODO URL [JSON] [CHAVE]
chamar() {
  local metodo=$1 url=$2 corpo=${3:-} chave=${4:-}
  local args=(-s -X "$metodo" -w '\n%{http_code}' -H 'Content-Type: application/json')
  [[ -n $corpo ]] && args+=(-d "$corpo")
  [[ -n $chave ]] && args+=(-H "Idempotency-Key: $chave")
  local resposta; resposta=$(curl "${args[@]}" "$url")
  CODIGO=${resposta##*$'\n'}
  CORPO=${resposta%$'\n'*}
  printf '  HTTP %s  %s\n' "$CODIGO" "$CORPO" | cut -c1-220
}

campo() { grep -o "\"$1\":\"[^\"]*\"" <<< "$CORPO" | head -1 | cut -d'"' -f4; }

exigir() { [[ $CODIGO == "$1" ]] || { echo "  ✘ esperado HTTP $1, veio $CODIGO" >&2; exit 1; }; }

# CPF válido gerado na hora (dígitos verificadores módulo 11) — não é de ninguém.
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

passo "1. Cadastrar duas clientes (o CPF volta mascarado)"
chamar POST "$CONTAS/v1/clientes" "{\"nome\":\"Maria Exemplo\",\"cpf\":\"$(gerar_cpf)\"}"; exigir 201
MARIA=$(campo id)
chamar POST "$CONTAS/v1/clientes" "{\"nome\":\"Joana Exemplo\",\"cpf\":\"$(gerar_cpf)\"}"; exigir 201
JOANA=$(campo id)

# Desde a Fase 2, o razão fica sabendo da conta pelo evento ContaAberta (Outbox + Kafka, ADR-0014):
# alguns milissegundos depois de ela nascer no serviço de contas. Logo depois de subir os serviços,
# o consumidor do razão ainda leva uns 10 s para entrar no grupo do Kafka: por isso a espera vai até 30 s.
esperar_no_razao() {
  for _ in {1..300}; do
    [[ $(curl -s -o /dev/null -w '%{http_code}' "$RAZAO/v1/contas/$1") == 200 ]] && return
    sleep 0.1
  done
  echo "  ✘ o razão não recebeu a conta $1 em 30 s" >&2; exit 1
}

passo "2. Abrir uma conta para cada (nasce aqui; o razão recebe pelo evento ContaAberta)"
chamar POST "$CONTAS/v1/contas" "{\"clienteId\":\"$MARIA\"}"; exigir 201
CONTA_MARIA=$(campo id)
chamar POST "$CONTAS/v1/contas" "{\"clienteId\":\"$JOANA\"}"; exigir 201
CONTA_JOANA=$(campo id)
esperar_no_razao "$CONTA_MARIA"; esperar_no_razao "$CONTA_JOANA"
ok "as duas contas já existem no razão"

passo "3. Depositar R\$ 1.000,00 na conta da Maria"
CHAVE="deposito-$CONTA_MARIA"
chamar POST "$RAZAO/v1/depositos" "{\"contaId\":\"$CONTA_MARIA\",\"valor\":\"1000.00\",\"descricao\":\"depósito inicial\"}" "$CHAVE"; exigir 201

passo "4. O app reenvia o MESMO depósito (mesma Idempotency-Key): nada é movido de novo"
chamar POST "$RAZAO/v1/depositos" "{\"contaId\":\"$CONTA_MARIA\",\"valor\":\"1000.00\",\"descricao\":\"depósito inicial\"}" "$CHAVE"; exigir 200
ok "200 OK com o lançamento original — o saldo continua R\$ 1.000,00"

passo "5. Transferir R\$ 250,75 da Maria para a Joana"
chamar POST "$RAZAO/v1/transferencias" "{\"origemId\":\"$CONTA_MARIA\",\"destinoId\":\"$CONTA_JOANA\",\"valor\":\"250.75\",\"descricao\":\"aluguel\"}" "transf-$RANDOM$RANDOM"; exigir 201

passo "6. A Joana tenta sacar R\$ 2.000,00: saldo insuficiente"
chamar POST "$RAZAO/v1/saques" "{\"contaId\":\"$CONTA_JOANA\",\"valor\":\"2000.00\"}" "saque-$RANDOM$RANDOM"; exigir 422
ok "422 com type urn:ledgerly:erro:saldo-insuficiente"

passo "7. Saldos, consultados pelo serviço de contas (que pergunta ao razão)"
chamar GET "$CONTAS/v1/contas/$CONTA_MARIA"; exigir 200; ok "Maria: R\$ $(campo saldo)"
chamar GET "$CONTAS/v1/contas/$CONTA_JOANA"; exigir 200; ok "Joana: R\$ $(campo saldo)"

passo "8. Bloquear a conta da Joana: recebe, mas não paga"
chamar POST "$CONTAS/v1/contas/$CONTA_JOANA/bloqueio"; exigir 200
# O bloqueio chega ao razão pelo evento ContaBloqueada.
for _ in {1..50}; do curl -s "$RAZAO/v1/contas/$CONTA_JOANA" | grep -q BLOQUEADA && break; sleep 0.1; done
chamar POST "$RAZAO/v1/saques" "{\"contaId\":\"$CONTA_JOANA\",\"valor\":\"1.00\"}" "saque-$RANDOM$RANDOM"; exigir 422
chamar POST "$RAZAO/v1/transferencias" "{\"origemId\":\"$CONTA_MARIA\",\"destinoId\":\"$CONTA_JOANA\",\"valor\":\"10.00\"}" "transf-$RANDOM$RANDOM"; exigir 201
chamar DELETE "$CONTAS/v1/contas/$CONTA_JOANA/bloqueio"; exigir 200

passo "9. Encerrar a conta da Maria com saldo: recusado pelo razão"
chamar POST "$CONTAS/v1/contas/$CONTA_MARIA/encerramento"; exigir 422

passo "10. Extrato da Maria (do mais novo para o mais antigo, com saldo após cada lançamento)"
chamar GET "$RAZAO/v1/contas/$CONTA_MARIA/extrato?limite=10"; exigir 200

passo "11. Conferência do razão inteiro: balanço fecha, saldos batem com os lançamentos"
chamar GET "$RAZAO/v1/integridade"; exigir 200
grep -q '"integro":true' <<< "$CORPO" && ok "razão íntegro"

printf '\n\033[1;32mDemonstração concluída.\033[0m\n'
