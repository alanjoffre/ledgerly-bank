#!/usr/bin/env bash
# Demonstração da Fase 3: as contas quentes medidas antes e depois, e a conciliação em lote.
#
# Pré-requisito: infraestrutura no ar e o pacote do razão gerado (ver docs/guias/demo-fase-3.md).
# O script derruba e sobe o razão duas vezes, com 1 e com 16 sub-contas.
# Uso: ./scripts/demo-fase-3.sh [segundos de carga, padrão 15]
# Todos os dados são sintéticos.

set -euo pipefail
cd "$(dirname "$0")/.."

# Desde a Fase 4 os serviços exigem token: todo curl daqui leva o token de serviço (ADR-0027).
source scripts/lib/token.sh
usar_token_no_curl

SEGUNDOS=${1:-15}
RAZAO=${RAZAO_URL:-http://localhost:8082}

passo() { printf '\n\033[1;34m▶ %s\033[0m\n' "$*"; }
ok()    { printf '  \033[32m✔ %s\033[0m\n' "$*"; }

# Sobe o razão com N sub-contas e mede os depósitos. Imprime a vazão média (pedidos/s).
medir_depositos() {
  local subcontas=$1
  ./scripts/servicos.sh parar razao > /dev/null 2>&1 || true
  RAZAO_SUBCONTAS=$subcontas ./scripts/servicos.sh subir razao > /dev/null
  desempenho/carga/medir.sh depositos "demo-$subcontas-subcontas" 64 "$SEGUNDOS" > /dev/null
  local resumo=desempenho/resultados/carga/demo-$subcontas-subcontas-depositos.txt
  grep -q '"integro":true' "$resumo" || { echo "  ✘ o razão não ficou íntegro: ver $resumo" >&2; exit 1; }
  grep 'mean throughput' "$resumo" | awk -F'|' '{gsub(/ /, "", $2); print $2}'
}

percentil() { # arquivo percentil
  grep "$2th percentile" "$1" | awk -F'|' '{gsub(/ /, "", $2); print $2}'
}

passo "1. Depósitos com o Caixa numa linha só (RAZAO_SUBCONTAS=1): todos fazem fila na mesma trava"
UMA=$(medir_depositos 1)
ok "$UMA depósitos por segundo · p99 $(percentil desempenho/resultados/carga/demo-1-subcontas-depositos.txt 99) ms · razão íntegro"

passo "2. Depósitos com o Caixa em 16 sub-contas: cada depósito trava só a do cliente"
DEZESSEIS=$(medir_depositos 16)
ok "$DEZESSEIS depósitos por segundo · p99 $(percentil desempenho/resultados/carga/demo-16-subcontas-depositos.txt 99) ms · razão íntegro"
ok "ganho: $(awk -v a="$UMA" -v b="$DEZESSEIS" 'BEGIN { printf "%.1f", b / a }')× (mesmo código, mesma máquina, minutos de diferença)"

passo "3. O saldo do Caixa é a soma das sub-contas (GET /v1/integridade)"
curl -s "$RAZAO/v1/integridade" | grep -o '"contasInternas":\[[^]]*\]'
ok "sub-conta do Caixa pode ficar negativa; o total, não — e é o total que a conferência olha"

passo "4. Conciliação em lote: 2 milhões de lançamentos Pix × razão, em sequência e em paralelo"
lotes/conciliacao/medir.sh 2000000 "$(nproc)" 2 | tail -n +2

printf '\n\033[1;32mDemonstração da Fase 3 concluída.\033[0m\n'
printf 'Relatórios do Gatling, com os gráficos: desempenho/carga/target/gatling/*/index.html\n'
