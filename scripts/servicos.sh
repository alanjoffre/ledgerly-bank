#!/usr/bin/env bash
# Sobe e derruba os serviços do Ledgerly Bank na sua máquina, a partir dos pacotes já compilados.
#
# Pré-requisitos: infraestrutura no ar (docker compose -f infra/docker/compose.yaml up -d --wait)
#                 e os pacotes gerados (./mvnw package -DskipTests).
#
# Uso:
#   ./scripts/servicos.sh subir              sobe os seis serviços e os dois gateways, e espera cada um ficar saudável
#   ./scripts/servicos.sh parar              derruba todos
#   ./scripts/servicos.sh subir razao        sobe um só (contas, razao, antifraude, pix, extrato, assistente, gateway-ib ou gateway-bo)
#   ./scripts/servicos.sh parar razao        derruba um só — para ver a Saga do Pix esperar por ele
#   ./scripts/servicos.sh situacao           quem está no ar
#
# Os gateways (BFF) servem a tela construída: rode antes "npm run construir" em web/ (ADR-0025).
#   gateway-ib → internet banking, http://localhost:8080      gateway-bo → backoffice, http://localhost:8090
#
# Os logs ficam em .servicos/<serviço>.log (a pasta não vai para o Git).
#
# Com LEDGERLY_OTEL=1, os serviços enviam rastros e métricas ao otel-lgtm do perfil "observabilidade"
# do compose (ADR-0042): LEDGERLY_OTEL=1 ./scripts/servicos.sh subir

set -euo pipefail
cd "$(dirname "$0")/.."

PASTA=.servicos
# Spring Boot liga com "true", Quarkus com "desligado = false": cada um com a sua propriedade.
if [[ ${LEDGERLY_OTEL:-} == 1 ]]; then
  export OTEL_LIGADO=true OTEL_SDK_DESLIGADO=false
fi
mkdir -p "$PASTA"
# O antifraude sobe antes do Pix: sem ele, todo Pix acima de R$ 500,00 ficaria retido (ADR-0033).
# O assistente usa o pgvector e, para responder de verdade, o Ollama (perfil "ia" do compose);
# sem o Ollama ele sobe e responde a mensagem de reserva (ADR-0036).
TODOS=(razao contas antifraude pix extrato assistente gateway-ib gateway-bo)

pacote() {
  case $1 in
    pix | antifraude) echo servicos/"$1"/target/quarkus-app/quarkus-run.jar ;;
    gateway-*) ls servicos/gateway/target/gateway-*.jar 2>/dev/null | grep -v original | head -1 ;;
    *) ls servicos/"$1"/target/"$1"-*.jar 2>/dev/null | grep -v original | head -1 ;;
  esac
}

saude() {
  case $1 in
    contas) echo http://localhost:8081/actuator/health ;;
    razao) echo http://localhost:8082/actuator/health ;;
    pix) echo http://localhost:8083/q/health/ready ;;
    antifraude) echo http://localhost:8085/q/health/ready ;;
    extrato) echo http://localhost:8084/actuator/health ;;
    assistente) echo http://localhost:8086/actuator/health ;;
    gateway-ib) echo http://localhost:8080/actuator/health ;;
    gateway-bo) echo http://localhost:8090/actuator/health ;;
  esac
}

# O mesmo pacote do gateway sobe em dois perfis: um BFF por front-end.
argumentos() {
  case $1 in
    gateway-ib) echo --spring.profiles.active=internet-banking ;;
    gateway-bo) echo --spring.profiles.active=backoffice ;;
  esac
}

# Quem já tinha o volume do Postgres das fases anteriores não tem o banco do antifraude (o init
# do contêiner só roda com o volume vazio). O SQL é o mesmo do init, e pode rodar de novo.
banco_do_antifraude() {
  docker compose -f infra/docker/compose.yaml exec -T postgres \
    psql -q -v ON_ERROR_STOP=1 -U ledgerly_admin -d postgres -f /docker-entrypoint-initdb.d/02-antifraude.sql > /dev/null
}

no_ar() { [[ -f $PASTA/$1.pid ]] && kill -0 "$(cat "$PASTA/$1.pid")" 2>/dev/null; }

subir() {
  local nome=$1 jar
  if no_ar "$nome"; then echo "  $nome já está no ar"; return; fi
  jar=$(pacote "$nome")
  [[ -f $jar ]] || { echo "  ✘ pacote de $nome não encontrado: rode ./mvnw package -DskipTests" >&2; exit 1; }
  [[ $nome == antifraude ]] && banco_do_antifraude
  # shellcheck disable=SC2046 # argumentos() devolve zero ou uma palavra
  nohup java -jar "$jar" $(argumentos "$nome") > "$PASTA/$nome.log" 2>&1 &
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
