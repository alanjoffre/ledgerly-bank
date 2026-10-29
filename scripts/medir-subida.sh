#!/usr/bin/env bash
# Mede, para cada serviço, o tempo de subida e a memória — a comparação Spring Boot x Quarkus da
# ADR-0017.
#
#   tempo de subida = do "java -jar" até a verificação de saúde responder pronto (inclui conectar
#                     no banco, aplicar o Flyway e entrar no Kafka — o que importa para escalar);
#   memória (RSS)   = memória física do processo logo depois de pronto, e depois de 500 requisições.
#
# Cada serviço sobe sozinho, RODADAS vezes; vale a mediana. Pré-requisitos: infraestrutura no ar,
# pacotes gerados (./mvnw package -DskipTests) e os serviços parados (./scripts/servicos.sh parar).
# A imagem nativa do Pix entra se existir (./mvnw -pl servicos/pix -am package -Pnativo -DskipTests).
#
# Uso: ./scripts/medir-subida.sh [rodadas]

set -euo pipefail
cd "$(dirname "$0")/.."

# Desde a Fase 4 os serviços exigem token: todo curl daqui leva o token de serviço (ADR-0027).
source scripts/lib/token.sh
usar_token_no_curl

RODADAS=${1:-5}

declare -A COMANDO SAUDE CONSULTA
COMANDO[razao]="java -jar $(ls servicos/razao/target/razao-*.jar | grep -v original | head -1)"
SAUDE[razao]=http://localhost:8082/actuator/health
CONSULTA[razao]=http://localhost:8082/v1/integridade
COMANDO[extrato]="java -jar $(ls servicos/extrato/target/extrato-*.jar | grep -v original | head -1)"
SAUDE[extrato]=http://localhost:8084/actuator/health
CONSULTA[extrato]=http://localhost:8084/v1/contas/00000000-0000-0000-0000-000000000009/saldo
COMANDO[pix]="java -jar servicos/pix/target/quarkus-app/quarkus-run.jar"
SAUDE[pix]=http://localhost:8083/q/health/ready
CONSULTA[pix]=http://localhost:8083/v1/chaves/ninguem@exemplo.com
SERVICOS=(razao extrato pix)
NATIVO=$(ls servicos/pix/target/pix-*-runner 2>/dev/null | head -1 || true)
if [[ -n $NATIVO ]]; then
  COMANDO[pix-nativo]="$NATIVO"
  SAUDE[pix-nativo]=${SAUDE[pix]}
  CONSULTA[pix-nativo]=${CONSULTA[pix]}
  SERVICOS+=(pix-nativo)
fi

# %s%N (nanossegundos) e a divisão, em vez de %3N: há versões do date que ignoram a largura do %N.
agora_ms() { echo $(( $(date +%s%N) / 1000000 )); }
rss_mb() { echo $(( $(ps -o rss= -p "$1") / 1024 )); }
mediana() { sort -n | awk '{v[NR]=$1} END {print (NR % 2) ? v[(NR+1)/2] : int((v[NR/2] + v[NR/2+1]) / 2)}'; }

printf '| Serviço | Subida (ms) | Memória ao subir (MB) | Memória após 500 requisições (MB) |\n|---|---|---|---|\n'
for servico in "${SERVICOS[@]}"; do
  subidas=() rss_subida=() rss_carga=()
  for ((i = 1; i <= RODADAS; i++)); do
    inicio=$(agora_ms)
    ${COMANDO[$servico]} > "/tmp/medir-$servico.log" 2>&1 &
    pid=$!
    until curl -sf "${SAUDE[$servico]}" > /dev/null; do
      kill -0 "$pid" 2>/dev/null || { echo "✘ $servico parou; veja /tmp/medir-$servico.log" >&2; exit 1; }
      sleep 0.02
    done
    subidas+=($(( $(agora_ms) - inicio )))
    rss_subida+=("$(rss_mb "$pid")")
    for _ in {1..500}; do curl -s -o /dev/null "${CONSULTA[$servico]}"; done
    rss_carga+=("$(rss_mb "$pid")")
    kill "$pid"; wait "$pid" 2>/dev/null || true
  done
  printf '| %s | %s | %s | %s |\n' "$servico" \
    "$(printf '%s\n' "${subidas[@]}" | mediana)" \
    "$(printf '%s\n' "${rss_subida[@]}" | mediana)" \
    "$(printf '%s\n' "${rss_carga[@]}" | mediana)"
done
