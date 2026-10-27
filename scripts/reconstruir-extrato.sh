#!/usr/bin/env bash
# Reconstrói o extrato do zero, relendo os eventos do Kafka desde o primeiro.
#
# Por que funciona: no CQRS, o MongoDB do extrato é um modelo de LEITURA, derivado dos eventos. A
# fonte da verdade é o razão, e o tópico razao.lancamentos.v1 guarda todos os lançamentos para
# sempre (retenção infinita). Apagar o extrato não perde nada: basta reler.
#
# Quando usar: mudou o formato do modelo de leitura, um bug gravou linhas erradas, ou o MongoDB se
# perdeu. Ver docs/adr/0016-extrato-em-cqrs-no-mongodb.md.
#
# Pré-requisito: infraestrutura de pé (docker compose ... up -d --wait) e o serviço extrato PARADO
# — o Kafka só deixa mudar os offsets de um grupo sem consumidores conectados.
# Uso: ./scripts/reconstruir-extrato.sh        (depois, suba o serviço extrato de novo)

set -euo pipefail

RAIZ=$(cd "$(dirname "$0")/.." && pwd)
COMPOSE=(docker compose -f "$RAIZ/infra/docker/compose.yaml")
EXTRATO=${EXTRATO_URL:-http://localhost:8084}
GRUPO=extrato
TOPICOS=(razao.lancamentos.v1 pix.transferencias.v1)

passo() { printf '\n\033[1;34m▶ %s\033[0m\n' "$*"; }
ok()    { printf '  \033[32m✔ %s\033[0m\n' "$*"; }

passo "1. Conferir que o serviço extrato está parado"
if curl -sf -o /dev/null "$EXTRATO/actuator/health"; then
  echo "  ✘ o serviço extrato está respondendo em $EXTRATO — pare-o antes de reconstruir" >&2
  exit 1
fi
ok "parado"

passo "2. Apagar o modelo de leitura (coleções do banco extrato no MongoDB)"
# </dev/null: sem isso o "exec -T" consome a entrada do próprio script.
"${COMPOSE[@]}" exec -T mongo mongosh --quiet -u extrato -p extrato_local --authenticationDatabase extrato extrato \
  --eval 'db.getCollectionNames().forEach(c => { db.getCollection(c).drop(); print("  apagada: " + c); })' </dev/null
ok "extrato vazio (os índices são recriados quando o serviço sobe)"

passo "3. Voltar o grupo '$GRUPO' para o primeiro evento de cada tópico"
args=()
for topico in "${TOPICOS[@]}"; do args+=(--topic "$topico"); done
"${COMPOSE[@]}" exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group "$GRUPO" --reset-offsets --to-earliest "${args[@]}" --execute </dev/null
ok "offsets no início"

passo "Pronto. Suba o serviço extrato: ele relê todos os eventos e o extrato volta inteiro."
