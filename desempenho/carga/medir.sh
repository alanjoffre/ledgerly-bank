#!/usr/bin/env bash
# Roda um cenário do teste de carga e guarda o resumo dos números.
#
# Pré-requisito: a infraestrutura e o razão no ar (./scripts/servicos.sh subir razao).
#
# Uso:
#   desempenho/carga/medir.sh <cenário> <rótulo> [usuários] [segundos]
#   desempenho/carga/medir.sh depositos antes-1-subconta
#
# Cenários: depositos, pix-envios, transferencias (ver ContasQuentesSimulacao).
# O resumo vai para desempenho/resultados/carga/<rótulo>-<cenário>.txt (esse vai para o Git);
# o relatório HTML, com os gráficos, fica em desempenho/carga/target/gatling/ (esse não vai).

set -euo pipefail
cd "$(dirname "$0")/../.."

cenario=${1:?informe o cenário: depositos, pix-envios ou transferencias}
rotulo=${2:?informe um rótulo para a medição, por exemplo antes-1-subconta}
usuarios=${3:-64}
segundos=${4:-30}

destino=desempenho/resultados/carga
mkdir -p "$destino"
saida=$(mktemp)
trap 'rm -f "$saida"' EXIT

./mvnw -q -B -pl desempenho/carga gatling:test \
  -Dcenario="$cenario" -Dusuarios="$usuarios" -Dsegundos="$segundos" > "$saida" 2>&1 || true

relatorio=$(ls -td desempenho/carga/target/gatling/*/ | head -1)
{
  echo "Cenário: $cenario · rótulo: $rotulo · $usuarios usuários simultâneos por $segundos s"
  echo "Quando: $(date '+%Y-%m-%d %H:%M') · máquina: $(nproc) núcleos, $(free -g | awk '/Mem:/ {print $2}') GB"
  grep -E "^Integridade" "$saida" || true
  # O bloco "Global Information" do Gatling: total, vazão média e percentis da latência.
  sed -n '/Global Information/,/^=\{10,\}/p' "$saida"
  echo "Relatório com gráficos: ${relatorio}index.html"
} | tee "$destino/$rotulo-$cenario.txt"
