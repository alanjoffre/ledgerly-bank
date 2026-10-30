#!/usr/bin/env bash
# Mede o circuit breaker do antifraude (ADR-0041): o Pix sob carga com o antifraude travado.
#
# O antifraude é congelado com SIGSTOP: a conexão entra, e a resposta não vem, como num serviço
# travado de verdade. Depois volta com SIGCONT, e a medição anota quando o Pix torna a ter nota.
#
# Pré-requisito: a infraestrutura no ar, os pacotes gerados e ./scripts/semear.sh já rodado.
# Precisa de: razao, contas, antifraude e pix. O script sobe os que faltam e religa o pix.
#
# Uso:
#   desempenho/circuito/medir.sh com-circuito
#   desempenho/circuito/medir.sh sem-circuito   (prova reversa: o mesmo pacote, com o circuito desligado)
#
# O resumo vai para desempenho/resultados/circuito/<rótulo>.txt (esse vai para o Git).

set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/lib/token.sh

rotulo=${1:?informe com-circuito ou sem-circuito}
case $rotulo in
  com-circuito) ;;
  # Variável da especificação MicroProfile Fault Tolerance: desliga tudo menos o @Fallback.
  sem-circuito) export MP_Fault_Tolerance_NonFallback_Enabled=false ;;
  *) echo "uso: $0 com-circuito|sem-circuito" >&2; exit 2 ;;
esac

./scripts/servicos.sh subir razao contas antifraude
./scripts/servicos.sh parar pix
./scripts/servicos.sh subir pix

antifraude=$(cat .servicos/antifraude.pid)
# Saindo como for, o antifraude não fica congelado.
trap 'kill -CONT "$antifraude" 2>/dev/null || true' EXIT
# A variante sem circuito não fica no ar depois da medição: o pix volta com o circuito ligado.
if [[ $rotulo == sem-circuito ]]; then
  trap 'kill -CONT "$antifraude" 2>/dev/null || true; unset MP_Fault_Tolerance_NonFallback_Enabled; ./scripts/servicos.sh parar pix; ./scripts/servicos.sh subir pix' EXIT
fi

mkdir -p desempenho/resultados/circuito
# Um token novo: o da medição é um só, e ela passa de 1 min.
rm -f "$ARQUIVO_DO_TOKEN"
token > /dev/null
python3 desempenho/circuito/medir.py "$rotulo" "$antifraude" "$ARQUIVO_DO_TOKEN" \
  | tee "desempenho/resultados/circuito/$rotulo.txt"
