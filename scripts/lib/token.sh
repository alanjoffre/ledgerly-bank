# Token de serviço para os scripts (client credentials do cliente "ledgerly-ferramentas").
#
# Desde a Fase 4 todo serviço exige token (ADR-0027). Os scripts de demonstração, a semeadura e as
# medições pedem um ao Keycloak com o segredo LOCAL do cliente de ferramentas — que tem o papel
# "servico" e pode gravar o cliente_id nos usuários do realm.
#
# Uso:   source scripts/lib/token.sh
#        command curl -H "Authorization: Bearer $(token)" ...
#   ou   usar_token_no_curl      # daqui em diante, todo "curl" do script leva o token
#
# O token vale 5 minutos. Fica num arquivo (só do seu usuário) e é renovado depois de 4: assim
# serve também dentro de $(...) e de "bash -c", que não enxergam variáveis alteradas no script.

EMISSOR=${LEDGERLY_EMISSOR:-http://localhost:18081/realms/ledgerly}
ARQUIVO_DO_TOKEN=${TMPDIR:-/tmp}/ledgerly-token-$UID
export EMISSOR ARQUIVO_DO_TOKEN

token() {
  local agora; agora=$(date +%s)
  if [[ ! -s $ARQUIVO_DO_TOKEN ]] || (( agora - $(stat -c %Y "$ARQUIVO_DO_TOKEN") > 240 )); then
    local resposta
    resposta=$(command curl -sf -d grant_type=client_credentials \
      -d client_id="${FERRAMENTAS_ID:-ledgerly-ferramentas}" \
      -d client_secret="${FERRAMENTAS_SEGREDO:-ferramentas-segredo-local}" \
      "$EMISSOR/protocol/openid-connect/token") \
      || { echo "  ✘ sem token: o Keycloak está no ar em ${EMISSOR%/realms/*}?" >&2; return 1; }
    (umask 077; grep -o '"access_token":"[^"]*"' <<< "$resposta" | cut -d'"' -f4 > "$ARQUIVO_DO_TOKEN")
  fi
  cat "$ARQUIVO_DO_TOKEN"
}

usar_token_no_curl() {
  curl() { command curl -H "Authorization: Bearer $(token)" "$@"; }
  export -f curl token
}
