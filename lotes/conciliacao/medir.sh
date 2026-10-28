#!/usr/bin/env bash
# Mede a conciliação em lote (sequência × paralelo) e guarda o resumo.
#
# Uso: lotes/conciliacao/medir.sh [registros] [partes] [rodadas]
#      lotes/conciliacao/medir.sh 10000000 16
#
# O resumo vai para desempenho/resultados/conciliacao/<registros>-registros-<partes>-partes.txt.

set -euo pipefail
cd "$(dirname "$0")/../.."

registros=${1:-10000000}
partes=${2:-$(nproc)}
rodadas=${3:-3}

./mvnw -q -B -pl lotes/conciliacao -am compile
destino=desempenho/resultados/conciliacao
mkdir -p "$destino"
{
  echo "Quando: $(date '+%Y-%m-%d %H:%M') · máquina: $(nproc) núcleos, $(free -g | awk '/Mem:/ {print $2}') GB · $(java -version 2>&1 | head -1)"
  java --enable-preview -Xmx2g -cp lotes/conciliacao/target/classes \
    io.github.alanjoffre.ledgerly.conciliacao.ConciliacaoEmLote "$registros" "$partes" "$rodadas"
} | tee "$destino/$registros-registros-$partes-partes.txt"
