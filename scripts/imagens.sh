#!/usr/bin/env bash
# Constrói as imagens Docker do Ledgerly Bank (Fase 6, ADR-0038), a partir do Dockerfile da raiz.
#
# Não precisa do Java nem do Node na máquina: o build acontece dentro dos estágios do Dockerfile.
# Precisa só do Docker (com o BuildKit, o padrão desde o Docker 23) e de um shell Linux comum.
#
# Uso:
#   ./scripts/imagens.sh construir                constrói todas (a nativa por último: é a mais cara)
#   ./scripts/imagens.sh construir razao pix      só as pedidas
#   ./scripts/imagens.sh tamanhos                 o tamanho de cada imagem
#
# Imagens (todas com a tag ledgerly/<nome>:0.6.0):
#   razao contas extrato assistente   Spring Boot, na JVM
#   pix antifraude                    Quarkus, na JVM
#   gateway-ib gateway-bo             os dois BFFs, cada um com a sua tela (internet banking, backoffice)
#   pix-nativo                        o pix compilado para código de máquina (GraalVM/Mandrel)
#
# O tempo de cada build sai na tela e fica em .servicos/imagens.log (a pasta não vai para o Git).

set -euo pipefail
cd "$(dirname "$0")/.."

VERSAO=${VERSAO:-0.6.0}
TODAS=(razao contas extrato assistente antifraude pix gateway-ib gateway-bo pix-nativo)
mkdir -p .servicos
REGISTRO=.servicos/imagens.log

# O estágio final (--target) e os argumentos de cada imagem.
argumentos() {
  case $1 in
    razao | contas | extrato | assistente) echo --target spring --build-arg SERVICO="$1" ;;
    pix | antifraude) echo --target quarkus --build-arg SERVICO="$1" ;;
    gateway-ib) echo --target gateway --build-arg TELA=internet-banking ;;
    gateway-bo) echo --target gateway --build-arg TELA=backoffice ;;
    pix-nativo) echo --target pix-nativo ;;
    *) echo "  ✘ imagem desconhecida: $1 (conheço: ${TODAS[*]})" >&2; return 1 ;;
  esac
}

construir() {
  local nome=$1 args inicio
  args=$(argumentos "$nome")
  inicio=$(date +%s.%N)
  # shellcheck disable=SC2086 # args são várias palavras de propósito
  if ! docker build $args --tag "ledgerly/$nome:$VERSAO" --progress "${PROGRESSO:-quiet}" . > /dev/null; then
    echo "  ✘ $nome: o build falhou (rode com PROGRESSO=plain para ver o passo)" >&2
    exit 1
  fi
  local segundos; segundos=$(awk -v fim="$(date +%s.%N)" -v inicio="$inicio" 'BEGIN { print fim - inicio }')
  printf '  ✔ ledgerly/%s:%s em %.1f s (%s)\n' "$nome" "$VERSAO" "$segundos" "$(tamanho "$nome")"
  printf '%s %s %.1f s %s\n' "$(date -Iseconds)" "$nome" "$segundos" "$(tamanho "$nome")" >> "$REGISTRO"
}

# O tamanho da imagem descompactada, como o "docker images" mostra.
tamanho() { docker image inspect "ledgerly/$1:$VERSAO" --format '{{.Size}}' | awk '{printf "%.0f MB", $1/1000/1000}'; }

acao=${1:-construir}
alvos=("${@:2}")
(( ${#alvos[@]} )) || alvos=("${TODAS[@]}")

case $acao in
  construir) for i in "${alvos[@]}"; do construir "$i"; done ;;
  tamanhos)
    for i in "${alvos[@]}"; do
      if docker image inspect "ledgerly/$i:$VERSAO" > /dev/null 2>&1; then
        printf '  %-12s %s\n' "$i" "$(tamanho "$i")"
      else
        printf '  %-12s (não construída)\n' "$i"
      fi
    done ;;
  *) echo "uso: $0 construir|tamanhos [imagem...]" >&2; exit 2 ;;
esac
