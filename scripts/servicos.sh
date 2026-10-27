#!/usr/bin/env bash
# Sobe e derruba os serviços do Ledgerly Bank na sua máquina, a partir dos pacotes já compilados.
#
# Pré-requisitos: infraestrutura no ar (docker compose -f infra/docker/compose.yaml up -d --wait)
#                 e os pacotes gerados (./mvnw package -DskipTests).
#
# Uso:
#   ./scripts/servicos.sh subir              sobe os quatro e espera cada um ficar saudável
#   ./scripts/servicos.sh parar              derruba os quatro
#   ./scripts/servicos.sh subir razao        sobe um só (contas, razao, pix ou extrato)
#   ./scripts/servicos.sh parar razao        derruba um só — para ver a Saga do Pix esperar por ele
#   ./scripts/servicos.sh situacao           quem está no ar
#
# Os logs ficam em .servicos/<serviço>.log (a pasta não vai para o Git).

set -euo pipefail
cd "$(dirname "$0")/.."

PASTA=.servicos
mkdir -p "$PASTA"
TODOS=(razao contas pix extrato)

pacote() {
  case $1 in
    pix) echo servicos/pix/target/quarkus-app/quarkus-run.jar ;;
    *) ls servicos/"$1"/target/"$1"-*.jar 2>/dev/null | grep -v original | head -1 ;;
  esac
}

saude() {
  case $1 in
    contas) echo http://localhost:8081/actuator/health ;;
    razao) echo http://localhost:8082/actuator/health ;;
    pix) echo http://localhost:8083/q/health/ready ;;
    extrato) echo http://localhost:8084/actuator/health ;;
  esac
}

no_ar() { [[ -f $PASTA/$1.pid ]] && kill -0 "$(cat "$PASTA/$1.pid")" 2>/dev/null; }

subir() {
  local nome=$1 jar
  if no_ar "$nome"; then echo "  $nome já está no ar"; return; fi
  jar=$(pacote "$nome")
  [[ -f $jar ]] || { echo "  ✘ pacote de $nome não encontrado: rode ./mvnw package -DskipTests" >&2; exit 1; }
  nohup java -jar "$jar" > "$PASTA/$nome.log" 2>&1 &
  echo $! > "$PASTA/$nome.pid"
  local inicio=$SECONDS
  until curl -sf "$(saude "$nome")" > /dev/null; do
    no_ar "$nome" || { echo "  ✘ $nome parou ao subir; veja $PASTA/$nome.log" >&2; exit 1; }
    (( SECONDS - inicio > 90 )) && { echo "  ✘ $nome não ficou saudável em 90 s" >&2; exit 1; }
    sleep 0.5
  done
  echo "  ✔ $nome no ar (pid $(cat "$PASTA/$nome.pid"))"
}

parar() {
  local nome=$1
  if no_ar "$nome"; then
    kill "$(cat "$PASTA/$nome.pid")"
    while no_ar "$nome"; do sleep 0.2; done
    echo "  ✔ $nome parado"
  fi
  rm -f "$PASTA/$nome.pid"
}

acao=${1:-situacao}
alvos=("${@:2}")
(( ${#alvos[@]} )) || alvos=("${TODOS[@]}")

case $acao in
  subir) for s in "${alvos[@]}"; do subir "$s"; done ;;
  parar) for s in "${alvos[@]}"; do parar "$s"; done ;;
  situacao) for s in "${alvos[@]}"; do no_ar "$s" && echo "  $s: no ar" || echo "  $s: parado"; done ;;
  *) echo "uso: $0 subir|parar|situacao [serviço...]" >&2; exit 2 ;;
esac
