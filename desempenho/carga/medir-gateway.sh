#!/usr/bin/env bash
# Mede o custo do salto pelo gateway (BFF): a mesma leitura de conta, direto no serviço contas e
# pelo gateway do internet banking, com a mesma carga (ver GatewaySimulacao).
#
# Pré-requisito: tudo no ar e semeado (./scripts/servicos.sh subir, ./scripts/semear.sh) e as
# dependências de web/ instaladas (npm ci): a sessão vem de um login de verdade, pelo Playwright.
#
# Uso: desempenho/carga/medir-gateway.sh [usuários] [segundos]
# Os resumos vão para desempenho/resultados/carga/gateway-direto.txt e gateway-bff.txt.
set -euo pipefail
cd "$(dirname "$0")/../.."
usuarios=${1:-16}
segundos=${2:-30}
destino=desempenho/resultados/carga
mkdir -p "$destino"

sessao=$(cd web/e2e && node sessao.mjs ana ana-local http://localhost:8080)
conta=$(sed -E 's/^conta=([^ ]+) .*/\1/' <<< "$sessao")
cookie=$(sed -E 's/.* cookie=(.*)$/\1/' <<< "$sessao")

saida=$(mktemp)
trap 'rm -f "$saida"' EXIT
for alvo in direto bff; do
  ./mvnw -q -B -pl desempenho/carga gatling:test -Dsimulacao=io.github.alanjoffre.ledgerly.carga.GatewaySimulacao \
    -Dalvo="$alvo" -Dconta="$conta" -Dcookie="$cookie" -Dusuarios="$usuarios" -Dsegundos="$segundos" > "$saida" 2>&1 || true
  {
    echo "Leitura de conta, alvo: $alvo · $usuarios usuários simultâneos por $segundos s"
    echo "Quando: $(date '+%Y-%m-%d %H:%M') · máquina: $(nproc) núcleos, $(free -g | awk '/Mem:/ {print $2}') GB"
    sed -n '/Global Information/,/^=\{10,\}/p' "$saida"
  } | tee "$destino/gateway-$alvo.txt"
done
