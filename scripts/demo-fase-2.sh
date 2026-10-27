#!/usr/bin/env bash
# Demonstração da Fase 2: Pix de ponta a ponta, com os quatro serviços de verdade.
#
# Pré-requisito: infraestrutura no ar e os serviços subidos por ./scripts/servicos.sh subir
# (ver docs/guias/demo-fase-2.md). O passo 9 derruba e sobe o razão pelo mesmo script.
# Uso: ./scripts/demo-fase-2.sh
# Só precisa de bash e curl. Todos os dados são fictícios; os CPFs são gerados na hora.

set -euo pipefail
cd "$(dirname "$0")/.."

CONTAS=${CONTAS_URL:-http://localhost:8081}
RAZAO=${RAZAO_URL:-http://localhost:8082}
PIX=${PIX_URL:-http://localhost:8083}
EXTRATO=${EXTRATO_URL:-http://localhost:8084}
LIQUIDACAO=00000000-0000-0000-0000-000000000002

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
  printf '  HTTP %s  %s\n' "$CODIGO" "$CORPO" | cut -c1-240
}

campo() { grep -o "\"$1\":\"[^\"]*\"" <<< "$CORPO" | head -1 | cut -d'"' -f4; }

exigir() { [[ $CODIGO == "$1" ]] || { echo "  ✘ esperado HTTP $1, veio $CODIGO" >&2; exit 1; }; }

# Espera (até 10 s) o comando dar certo — para o que chega por evento, alguns ms depois.
esperar() {
  local descricao=$1; shift
  for _ in {1..100}; do "$@" > /dev/null 2>&1 && return; sleep 0.1; done
  echo "  ✘ $descricao: não aconteceu em 10 s" >&2; exit 1
}

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

nova_conta() { # nome cpf → imprime o id da conta
  chamar POST "$CONTAS/v1/clientes" "{\"nome\":\"$1\",\"cpf\":\"$2\"}" > /dev/null; exigir 201
  local cliente; cliente=$(campo id)
  chamar POST "$CONTAS/v1/contas" "{\"clienteId\":\"$cliente\"}" > /dev/null; exigir 201
  local conta; conta=$(campo id)
  esperar "o razão receber a conta" curl -sf "$RAZAO/v1/contas/$conta"
  echo "$conta"
}

pix() { # chave-idempotencia pagador chave valor
  chamar POST "$PIX/v1/transferencias" "{\"pagadorContaId\":\"$2\",\"chave\":\"$3\",\"valor\":\"$4\",\"descricao\":\"demo\"}" "$1"
}

passo "1. Três clientes com conta: João, Maria e Joana (o razão recebe cada conta pelo evento ContaAberta)"
CPF_MARIA=$(gerar_cpf)
JOAO=$(nova_conta "João da Silva" "$(gerar_cpf)")
MARIA=$(nova_conta "Maria Souza" "$CPF_MARIA")
JOANA=$(nova_conta "Joana Lima" "$(gerar_cpf)")
ok "contas criadas e já conhecidas pelo razão"

passo "2. Depositar R\$ 1.000,00 na conta do João"
chamar POST "$RAZAO/v1/depositos" "{\"contaId\":\"$JOAO\",\"valor\":\"1000.00\",\"descricao\":\"depósito inicial\"}" "dep-$JOAO"; exigir 201

passo "3. Maria registra as chaves Pix: e-mail e o CPF dela (o serviço de contas confere a titularidade)"
EMAIL_MARIA="maria.$RANDOM$RANDOM@exemplo.com"
chamar POST "$PIX/v1/chaves" "{\"contaId\":\"$MARIA\",\"tipo\":\"EMAIL\",\"valor\":\"$EMAIL_MARIA\"}"; exigir 201
chamar POST "$PIX/v1/chaves" "{\"contaId\":\"$MARIA\",\"tipo\":\"CPF\",\"valor\":\"$CPF_MARIA\"}"; exigir 201
chamar POST "$PIX/v1/chaves" "{\"contaId\":\"$MARIA\",\"tipo\":\"CPF\",\"valor\":\"$(gerar_cpf)\"}"; exigir 422
ok "o CPF de outra pessoa é recusado: titularidade-nao-confere"

passo "4. João confere o destinatário antes de pagar (a chave volta mascarada)"
chamar GET "$PIX/v1/chaves/$CPF_MARIA"; exigir 200

passo "5. Pix de R\$ 150,00 para a Maria, pela chave e-mail"
CHAVE_PIX="pix-$RANDOM$RANDOM"
pix "$CHAVE_PIX" "$JOAO" "$EMAIL_MARIA" "150.00"; exigir 201
[[ $(campo situacao) == CONCLUIDA ]] && ok "CONCLUIDA: débito na Liquidação Pix e crédito na Maria"

passo "6. O app reenvia o mesmo Pix (mesma Idempotency-Key): a mesma transferência, nada se move de novo"
pix "$CHAVE_PIX" "$JOAO" "$EMAIL_MARIA" "150.00"; exigir 200

passo "7. Maria gera um QR Code de R\$ 42,50; João paga pelo copia e cola"
chamar GET "$PIX/v1/chaves/$EMAIL_MARIA/qrcode?valor=42.50&descricao=Bolo"; exigir 200
COPIA_E_COLA=$(campo copiaECola)
chamar POST "$PIX/v1/transferencias" "{\"pagadorContaId\":\"$JOAO\",\"copiaECola\":\"$COPIA_E_COLA\"}" "qr-$RANDOM$RANDOM"; exigir 201
ADULTERADO=${COPIA_E_COLA/540542.50/540592.50}
chamar POST "$PIX/v1/qrcodes/leitura" "{\"copiaECola\":\"$ADULTERADO\"}"; exigir 400
ok "QR Code com o valor trocado: recusado pelo CRC"

passo "8. A Joana encerra a conta; o João faz um Pix para ela: o crédito é recusado e a Saga estorna"
chamar POST "$PIX/v1/chaves" "{\"contaId\":\"$JOANA\",\"tipo\":\"ALEATORIA\"}"; exigir 201
CHAVE_JOANA=$(campo chave)
chamar POST "$CONTAS/v1/contas/$JOANA/encerramento"; exigir 200
pix "est-$RANDOM$RANDOM" "$JOAO" "$CHAVE_JOANA" "30.00"; exigir 201
[[ $(campo situacao) == ESTORNADA ]] && ok "ESTORNADA: os R\$ 30,00 voltaram para o João"

passo "9. O razão cai no meio de um Pix — e a Saga termina sozinha quando ele volta"
./scripts/servicos.sh parar razao
CHAVE_QUEDA="queda-$RANDOM$RANDOM"
pix "$CHAVE_QUEDA" "$JOAO" "$EMAIL_MARIA" "20.00"; exigir 202
ID_QUEDA=$(campo id)
ok "202 Accepted: o pedido foi aceito, situação $(campo situacao), e espera o razão"
./scripts/servicos.sh subir razao
esperar "a Saga concluir" bash -c "curl -s '$PIX/v1/transferencias/$ID_QUEDA' | grep -q '\"situacao\":\"CONCLUIDA\"'"
chamar GET "$PIX/v1/transferencias/$ID_QUEDA"; exigir 200
ok "CONCLUIDA depois que o razão voltou, sem ninguém apertar nada"

passo "10. Extrato do João, montado pelos eventos (CQRS no MongoDB)"
esperar "o extrato mostrar o último Pix" bash -c "curl -s '$EXTRATO/v1/contas/$JOAO/extrato?limite=10' | grep -q '$ID_QUEDA'"
chamar GET "$EXTRATO/v1/contas/$JOAO/extrato?limite=10"; exigir 200
SALDO_EXTRATO=$(grep -o '"saldo":"[^"]*"' <<< "$CORPO" | head -1 | cut -d'"' -f4)
chamar GET "$RAZAO/v1/contas/$JOAO"; exigir 200
[[ $(campo saldo) == "$SALDO_EXTRATO" ]] && ok "saldo do extrato = saldo do razão: R\$ $SALDO_EXTRATO"

passo "11. Conferência: Liquidação Pix zerada e o razão íntegro"
chamar GET "$RAZAO/v1/contas/$LIQUIDACAO"; exigir 200
[[ $(campo saldo) == 0.00 ]] && ok "nenhum dinheiro parado em trânsito"
chamar GET "$RAZAO/v1/integridade"; exigir 200
grep -q '"integro":true' <<< "$CORPO" && ok "razão íntegro: o balanço fecha"

printf '\n\033[1;32mDemonstração da Fase 2 concluída.\033[0m\n'
