#!/usr/bin/env bash
# O Kubernetes local do Ledgerly Bank (Fase 6, ADR-0039): um cluster k3d com os oito serviços,
# instalados pelo Helm chart de infra/k8s/ledgerly, usando a infraestrutura do Docker Compose.
#
# O que é o k3d: o k3s (uma distribuição enxuta do Kubernetes) rodando DENTRO de um contêiner Docker.
# O "nó" do cluster é um contêiner; os pods rodam dentro dele. Sobe em ~20 s e some sem deixar rastro.
#
# Pré-requisitos:
#   docker compose -f infra/docker/compose.yaml up -d --wait     a infraestrutura (bancos, Kafka, Keycloak)
#   ./scripts/imagens.sh construir                               as imagens ledgerly/*:0.6.0
#   k3d, helm e kubectl no PATH (versões testadas: k3d 5.9.0, Helm 4.3.1, kubectl 1.36)
#   os serviços locais (scripts/servicos.sh) PARADOS: os BFFs do cluster usam as portas 8080 e 8090.
#
# Uso:
#   ./scripts/k8s.sh subir        cria o cluster (se não existir), importa as imagens e instala o chart
#   ./scripts/k8s.sh atualizar    só reinstala o chart (mudou um valor ou um modelo)
#   ./scripts/k8s.sh situacao     pods, HPA e uso de CPU e memória
#   ./scripts/k8s.sh derrubar     apaga o cluster (devolve a memória; a infra do compose fica)
#
# O kubectl dos comandos manuais precisa do arquivo de acesso ao cluster:
#   export KUBECONFIG=$PWD/.servicos/kubeconfig-k3d
# (fica fora do ~/.kube/config de propósito: este script não mexe nos clusters que você já tiver.)

set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER=ledgerly
NAMESPACE=ledgerly
# Versão exata do k3s (o Kubernetes dentro do nó): 1.36, a mesma linha do kubectl.
K3S=rancher/k3s:v1.36.5-k3s1
REDE=ledgerly_default
VERSAO=${VERSAO:-0.6.0}
IMAGENS=(razao contas extrato assistente antifraude pix pix-nativo gateway-ib gateway-bo)
PROXY=alpine/socat:1.8.0.3
INFRA=(postgres pgvector mongo redis kafka keycloak ollama)
mkdir -p .servicos
export KUBECONFIG=$PWD/.servicos/kubeconfig-k3d

existe() { k3d cluster list "$CLUSTER" > /dev/null 2>&1; }

criar_cluster() {
  if existe; then echo "  cluster $CLUSTER já existe"; else
    docker network inspect "$REDE" > /dev/null 2>&1 \
      || { echo "  ✘ a rede $REDE não existe: suba antes a infraestrutura do compose" >&2; exit 1; }
    # --network: o nó entra na MESMA rede Docker da infraestrutura, e enxerga os contêineres por IP.
    # --no-lb: sem o contêiner de balanceador do k3d (um nó só não precisa; economiza memória).
    # -p 127.0.0.1:8080:30080@server:0:direct: a porta 8080 da sua máquina (só local) vai para a porta
    #    30080 do nó, o NodePort do gateway-ib. Idem 8090 → 30090 (gateway-bo). O endereço de volta do
    #    login registrado no Keycloak é localhost:8080 e localhost:8090: as portas têm de ser essas.
    #    ":direct" publica a porta no próprio contêiner do nó — sem o balanceador, é o único jeito.
    # --disable traefik/servicelb: o Ingress e o balanceador embutidos do k3s não são usados aqui.
    #    O metrics-server (que o HPA lê) continua ligado.
    k3d cluster create "$CLUSTER" --image "$K3S" --network "$REDE" --no-lb \
      --servers 1 --agents 0 \
      -p "127.0.0.1:8080:30080@server:0:direct" -p "127.0.0.1:8090:30090@server:0:direct" \
      --k3s-arg "--disable=traefik@server:0" --k3s-arg "--disable=servicelb@server:0" \
      --kubeconfig-update-default=false --kubeconfig-switch-context=false --wait
  fi
  k3d kubeconfig get "$CLUSTER" > "$KUBECONFIG"
  chmod 600 "$KUBECONFIG"
}

# As imagens não estão em registro nenhum: vão do Docker da máquina para dentro do nó.
# Por que "docker save --platform" num arquivo, e não "k3d image import <imagem>" direto: com o
# armazenamento de imagens do containerd (padrão do Docker Desktop), uma imagem baixada é um *índice*
# com várias plataformas (amd64, arm64...), e a máquina só tem os pedaços (*blobs*) da sua. O save sem
# --platform exporta o índice inteiro, e o import no nó falha com "content digest ... not found".
importar_imagens() {
  local lista=() arquivo
  for i in "${IMAGENS[@]}"; do
    if docker image inspect "ledgerly/$i:$VERSAO" > /dev/null 2>&1; then lista+=("ledgerly/$i:$VERSAO")
    else echo "  ⚠ ledgerly/$i:$VERSAO não existe (./scripts/imagens.sh construir $i)" >&2; fi
  done
  docker image inspect "$PROXY" > /dev/null 2>&1 || docker pull -q "$PROXY" > /dev/null
  lista+=("$PROXY")
  arquivo=$(mktemp "${TMPDIR:-/tmp}/ledgerly-imagens-XXXXXX.tar")
  trap 'rm -f "$arquivo"' RETURN
  docker save --platform "linux/$(docker info -f '{{.Architecture}}' | sed 's/x86_64/amd64/;s/aarch64/arm64/')" \
    -o "$arquivo" "${lista[@]}"
  # O k3d não devolve erro quando o import falha no nó: confere-se pela saída.
  if k3d image import -c "$CLUSTER" "$arquivo" 2>&1 | grep -E 'ERRO|FATA' >&2; then
    echo "  ✘ falhou a importação das imagens" >&2; exit 1
  fi
  echo "  ✔ ${#lista[@]} imagens importadas no nó"
}

# O IP de cada contêiner da infraestrutura na rede do compose, como --set para o Helm.
ips_da_infra() {
  local nome ip
  for nome in "${INFRA[@]}"; do
    ip=$(docker inspect -f "{{(index .NetworkSettings.Networks \"$REDE\").IPAddress}}" "ledgerly-$nome-1" 2>/dev/null || true)
    # Contêiner parado (o Ollama, que é opcional) responde "invalid IP": só vale um IPv4 de verdade.
    if [[ $ip =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then printf -- '--set infraExterna.%s.ip=%s ' "$nome" "$ip"
    else echo "  ⚠ $nome não está no ar no compose: fica sem endereço no cluster" >&2; fi
  done
}

instalar() {
  # LEDGERLY_OTEL=1: exporta rastros e métricas ao lgtm do compose (perfil "observabilidade", ADR-0042).
  local telemetria=()
  if [[ ${LEDGERLY_OTEL:-} == 1 ]]; then INFRA+=(lgtm); telemetria=(--set telemetria.ligada=true); fi
  # shellcheck disable=SC2046 # ips_da_infra devolve vários argumentos de propósito
  helm upgrade --install ledgerly infra/k8s/ledgerly \
    --namespace "$NAMESPACE" --create-namespace \
    -f infra/k8s/ledgerly/values-k3d.yaml $(ips_da_infra) "${telemetria[@]}" "$@" \
    --wait --timeout 6m > /dev/null
  echo "  ✔ chart instalado; todos os pods prontos"
}

situacao() {
  existe || { echo "  cluster $CLUSTER não existe"; return; }
  kubectl get pods,hpa -n "$NAMESPACE" -o wide
  echo
  kubectl top pods -n "$NAMESPACE" 2>/dev/null || echo "  (o metrics-server ainda não tem números: espere ~1 min)"
}

case ${1:-situacao} in
  subir)
    criar_cluster
    importar_imagens
    instalar "${@:2}"
    echo "  Internet banking: http://localhost:8080    Backoffice: http://localhost:8090"
    echo "  kubectl: export KUBECONFIG=$KUBECONFIG" ;;
  atualizar) instalar "${@:2}" ;;
  situacao) situacao ;;
  derrubar) existe && k3d cluster delete "$CLUSTER"; rm -f "$KUBECONFIG"; echo "  ✔ cluster apagado" ;;
  *) echo "uso: $0 subir|atualizar|situacao|derrubar" >&2; exit 2 ;;
esac
