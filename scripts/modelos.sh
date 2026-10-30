#!/usr/bin/env bash
# Baixa, uma vez, os modelos que o Ollama local usa na Fase 5 (ADR-0034). Ficam no volume
# "ollama-modelos": um "down" não os apaga; só "down -v".
#
# Pré-requisito: docker compose -f infra/docker/compose.yaml --profile ia up -d --wait
#
#   qwen3:4b-instruct-2507-q4_K_M
#              o assistente "pergunte ao seu extrato": precisa chamar ferramentas (ADR-0036)
#   gemma3:4b  a explicação do alerta para a analista: só escreve texto (ADR-0035)
#   bge-m3     os vetores dos artigos de ajuda, em português (RAG)
#
# Os três juntos ocupam cerca de 7 GB no disco. Cabem, um de cada vez, numa placa de vídeo de 6 GB.

set -euo pipefail
cd "$(dirname "$0")/.."

MODELOS=(qwen3:4b-instruct-2507-q4_K_M gemma3:4b bge-m3)

for modelo in "${MODELOS[@]}"; do
  echo "→ $modelo"
  docker compose -f infra/docker/compose.yaml exec -T ollama ollama pull "$modelo"
done
docker compose -f infra/docker/compose.yaml exec -T ollama ollama list
