#!/usr/bin/env bash
# Confere o Terraform da AWS SEM conta na AWS e sem gastar nada (ADR-0006 e ADR-0040).
#
# Pré-requisitos: Terraform 1.17.1 (no PATH como "terraform" ou "terraform-1.17.1", ou na variável
#                 TERRAFORM), Docker e o aws-cli (v2). O tflint e o trivy rodam em contêiner.
#
# Uso:
#   ./scripts/nuvem.sh validar         formatação, validate, plan sem conta, regras do projeto, tflint e trivy (o que o CI roda)
#   ./scripts/nuvem.sh simular         sobe o simulador (Moto), aplica infra/terraform/local, confere
#                                      com o aws-cli, destrói e derruba o simulador
#   ./scripts/nuvem.sh simular-tudo    EXPERIMENTO: tenta aplicar a infraestrutura AWS inteira no
#                                      simulador e conta o que ele aceitou e o que recusou
#
# Nada aqui fala com a AWS de verdade. As credenciais são "test"/"test", falsas de propósito.
# O simulador ouve só em 127.0.0.1:15000 (troque com MOTO_PORTA=<porta>).

set -euo pipefail
cd "$(dirname "$0")/.."

RAIZ=infra/terraform
VERSAO_TERRAFORM=1.17.1

# Versões fixas, com o resumo (*digest*) da imagem: a tag pode ser movida, o resumo não.
# Todas com mais de duas semanas de publicadas em 30/10/2026 (cuidado com a cadeia de
# suprimentos: versão recém-saída ainda não teve tempo de alguém achar um problema nela).
IMAGEM_MOTO=motoserver/moto:5.2.4@sha256:120b1589c6df7e35d2092935f6ff724486bb2c4189d0e36ce795baba1400ffd2
IMAGEM_TFLINT=ghcr.io/terraform-linters/tflint:v0.64.0@sha256:1c595f42d794c32c45a6ea8b58655fd66433d4ca3b1bc631c574a48d120bd19f
IMAGEM_TRIVY=aquasec/trivy:0.75.0@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa

MOTO_PORTA=${MOTO_PORTA:-15000}
MOTO_ENDERECO=http://localhost:$MOTO_PORTA
MOTO_NOME=ledgerly-simulador-aws

# O cache dos provedores evita baixar de novo os ~700 MB do provedor da AWS a cada cópia.
export TF_PLUGIN_CACHE_DIR=${TF_PLUGIN_CACHE_DIR:-$HOME/.terraform.d/plugin-cache}
mkdir -p "$TF_PLUGIN_CACHE_DIR"
export TF_IN_AUTOMATION=1 TF_INPUT=0

titulo() { printf '\n== %s\n' "$*"; }
erro() { printf 'ERRO: %s\n' "$*" >&2; exit 1; }
agora() { date +%s; }
duracao() { echo "$(($(agora) - $1)) s"; }

achar_terraform() {
  local candidatos=("${TERRAFORM:-}" "terraform-$VERSAO_TERRAFORM" terraform)
  for c in "${candidatos[@]}"; do
    [ -n "$c" ] && command -v "$c" >/dev/null || continue
    if "$c" version -json | grep -q "\"terraform_version\": \"$VERSAO_TERRAFORM\""; then
      TF=$c
      return
    fi
  done
  erro "preciso do Terraform $VERSAO_TERRAFORM (o versions.tf exige exatamente esta). Instale ou aponte TERRAFORM=<caminho>."
}

# Copia uma raiz para uma pasta temporária, com um override ao lado. O código versionado fica
# limpo: as opções de "não conferir a conta" ou de "falar com o simulador" só existem na cópia.
TEMP=$(mktemp -d)
trap 'rm -rf "$TEMP"' EXIT
copiar() { # copiar <pasta de destino> <override ou vazio>
  mkdir -p "$1"
  cp -r "$RAIZ/aws" "$RAIZ/local" "$1/"
  rm -rf "$1/aws/.terraform" "$1/local/.terraform"
  if [ -n "${2:-}" ]; then cp "$RAIZ/validacao/$2" "$1/aws/"; fi
}

# ---------------------------------------------------------------------------------------------
validar() {
  achar_terraform
  local inicio
  inicio=$(agora)

  titulo "1/6 Formatação (terraform fmt): o código está no formato padrão?"
  "$TF" fmt -check -recursive -diff "$RAIZ" || erro "rode: $TF fmt -recursive $RAIZ"
  echo "ok"

  titulo "2/6 Sintaxe e tipos (terraform validate) das duas raízes"
  copiar "$TEMP/v" sem_conta_override.tf
  for raiz in aws local; do
    "$TF" -chdir="$TEMP/v/$raiz" init -backend=false -lockfile=readonly -no-color >/dev/null
    "$TF" -chdir="$TEMP/v/$raiz" validate -no-color
  done

  titulo "3/6 Plano (terraform plan) SEM conta: credencial falsa, nenhum pedido à AWS"
  local t
  t=$(agora)
  "$TF" -chdir="$TEMP/v/aws" plan -lock=false -no-color -out=plano.bin >"$TEMP/plano.txt" ||
    { cat "$TEMP/plano.txt"; erro "o plan falhou"; }
  grep -E '^Plan:' "$TEMP/plano.txt"
  echo "(plano em $(duracao "$t"))"

  titulo "4/6 Regras do projeto no plano: a internet só entra pelo balanceador, na porta 443"
  "$TF" -chdir="$TEMP/v/aws" show -json plano.bin | python3 -I "$RAIZ/validacao/conferir_plano.py"

  titulo "5/6 tflint: erro que o validate não pega (tipo de máquina, versão, variável sem uso)"
  docker run --rm -v "$PWD/$RAIZ:/dados:ro" -w /dados/aws \
    -e GITHUB_TOKEN="${GITHUB_TOKEN:-}" --entrypoint sh "$IMAGEM_TFLINT" -c \
    'tflint --config /dados/.tflint.hcl --init >/dev/null &&
     tflint --config /dados/.tflint.hcl --format compact &&
     tflint --config /dados/.tflint.hcl --chdir /dados/local --format compact'
  echo "ok"

  titulo "6/6 trivy: falha de segurança (porta aberta à internet, dado sem cifra, balde público)"
  docker run --rm -v "$PWD/$RAIZ:/dados:ro" -v ledgerly-cache-trivy:/root/.cache "$IMAGEM_TRIVY" \
    config --quiet --severity MEDIUM,HIGH,CRITICAL --exit-code 1 /dados

  titulo "VALIDAÇÃO OK em $(duracao "$inicio")"
}

# ---------------------------------------------------------------------------------------------
subir_simulador() { # subir_simulador [opções extras do docker run]
  docker rm -f "$MOTO_NOME" >/dev/null 2>&1 || true
  docker run -d --rm --name "$MOTO_NOME" -p "127.0.0.1:$MOTO_PORTA:5000" "$@" "$IMAGEM_MOTO" >/dev/null
  for _ in $(seq 1 60); do
    curl -sf "$MOTO_ENDERECO/moto-api/" >/dev/null && return
    sleep 0.5
  done
  erro "o simulador não respondeu em $MOTO_ENDERECO"
}

derrubar_simulador() { docker rm -f "$MOTO_NOME" >/dev/null 2>&1 || true; }

# O aws-cli falando com o simulador (nunca com a AWS).
aws_sim() {
  AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=sa-east-1 \
    AWS_PAGER="" aws --endpoint-url "$MOTO_ENDERECO" "$@"
}

FALHAS=0
confere() { # confere <o que> <obtido> <esperado>
  if [ "$2" = "$3" ]; then
    printf '  ok      %s\n' "$1"
  else
    printf '  FALHOU  %s (veio "%s", esperado "%s")\n' "$1" "$2" "$3"
    FALHAS=$((FALHAS + 1))
  fi
}

simular() {
  achar_terraform
  command -v aws >/dev/null || erro "preciso do aws-cli (v2) para conferir o que foi criado"
  local inicio t
  inicio=$(agora)
  local dir="$TEMP/s/local"
  local tf=("$TF" -chdir="$dir")
  # Se algo falhar no meio: o simulador guarda tudo na memória, então derrubá-lo já apaga o criado.
  trap 'derrubar_simulador; rm -rf "$TEMP"' EXIT

  titulo "Subindo o simulador (Moto) em $MOTO_ENDERECO"
  subir_simulador
  copiar "$TEMP/s" ""
  "${tf[@]}" init -lockfile=readonly -no-color >/dev/null

  titulo "Aplicando infra/terraform/local no simulador"
  t=$(agora)
  "${tf[@]}" apply -auto-approve -no-color -var "endereco_simulador=$MOTO_ENDERECO" >"$TEMP/apply.txt" ||
    { tail -40 "$TEMP/apply.txt"; erro "o apply falhou"; }
  grep -E '^Apply complete' "$TEMP/apply.txt"
  echo "(apply em $(duracao "$t"))"

  local balde fila mortas
  balde=$("${tf[@]}" output -raw balde)
  fila=$("${tf[@]}" output -raw fila)
  mortas=$("${tf[@]}" output -raw fila_mortas)

  titulo "Conferindo com o aws-cli o que o simulador guardou"
  confere "S3 com versionamento" \
    "$(aws_sim s3api get-bucket-versioning --bucket "$balde" --query Status --output text)" Enabled
  confere "S3 cifrado com chave KMS própria" \
    "$(aws_sim s3api get-bucket-encryption --bucket "$balde" \
      --query 'ServerSideEncryptionConfiguration.Rules[0].ApplyServerSideEncryptionByDefault.SSEAlgorithm' --output text)" aws:kms
  confere "S3 sem acesso público (as 4 travas)" \
    "$(aws_sim s3api get-public-access-block --bucket "$balde" \
      --query 'PublicAccessBlockConfiguration.[BlockPublicAcls,IgnorePublicAcls,BlockPublicPolicy,RestrictPublicBuckets]' --output text)" \
    "$(printf 'True\tTrue\tTrue\tTrue')"
  confere "S3 com trava de objeto (Object Lock) em modo GOVERNANCE" \
    "$(aws_sim s3api get-object-lock-configuration --bucket "$balde" \
      --query 'ObjectLockConfiguration.Rule.DefaultRetention.Mode' --output text)" GOVERNANCE
  confere "S3 move relatório velho para o Glacier Instant Retrieval" \
    "$(aws_sim s3api get-bucket-lifecycle-configuration --bucket "$balde" \
      --query 'Rules[0].Transitions[0].StorageClass' --output text)" GLACIER_IR
  confere "SQS manda para a fila de mortas após 5 tentativas" \
    "$(aws_sim sqs get-queue-attributes --queue-url "$fila" --attribute-names RedrivePolicy \
      --query 'Attributes.RedrivePolicy' --output text | python3 -c 'import json,sys; print(json.load(sys.stdin)["maxReceiveCount"])')" 5
  confere "Secrets Manager com os 2 segredos" \
    "$(aws_sim secretsmanager list-secrets --query 'length(SecretList[?starts_with(Name, `ledgerly-simulador/`)])' --output text)" 2

  # A prova de que a senha não está no estado do Terraform: lê a senha no simulador e procura no
  # estado. O valor não é impresso em lugar nenhum.
  local senha
  senha=$(aws_sim secretsmanager get-secret-value --secret-id ledgerly-simulador/keycloak/admin --query SecretString --output text)
  confere "senha gerada com 40 caracteres" "${#senha}" 40
  confere "senha ausente do estado do Terraform (write-only + ephemeral)" \
    "$(grep -c -F -- "$senha" "$dir/terraform.tfstate" || true)" 0
  unset senha

  titulo "Fluxo da conciliação: relatório no S3 vira aviso na fila"
  printf 'conta;saldo_razao;saldo_pix\nexemplo;0;0\n' >"$TEMP/relatorio.csv"
  local recusa
  recusa=$(aws_sim s3api put-object --bucket "$balde" --key conciliacao/2026-10-30.csv \
    --body "$TEMP/relatorio.csv" 2>&1 >/dev/null || true)
  confere "gravar sem TLS é recusado (política do balde só aceita TLS)" \
    "$(grep -o '(403)' <<<"$recusa" | head -1)" "(403)"

  # O Moto não preenche a condição aws:SecureTransport: ele recusa até o pedido por HTTPS
  # (conferido em 30/10/2026). Para testar o resto do fluxo, a política sai SÓ no simulador.
  aws_sim s3api delete-bucket-policy --bucket "$balde"
  aws_sim s3api put-object --bucket "$balde" --key conciliacao/2026-10-30.csv --body "$TEMP/relatorio.csv" >/dev/null

  # O S3 manda antes um evento de teste (s3:TestEvent) ao ligar o aviso: lê até 10 mensagens.
  # O nome do arquivo vem codificado como em URL (a barra vira %2F no Moto), daí o sed.
  local avisos
  avisos=$(aws_sim sqs receive-message --queue-url "$fila" --max-number-of-messages 10 \
    --wait-time-seconds 5 --query 'Messages[].Body' --output text)
  confere "aviso chegou na fila com o nome do relatório" \
    "$(sed 's/%2F/\//g' <<<"$avisos" | grep -o 'conciliacao/2026-10-30.csv' | head -1)" conciliacao/2026-10-30.csv
  aws_sim s3api put-object --bucket "$balde" --key outros/rascunho.csv --body "$TEMP/relatorio.csv" >/dev/null
  confere "arquivo fora de conciliacao/ não gera aviso" \
    "$(aws_sim sqs receive-message --queue-url "$fila" --wait-time-seconds 3 --query 'length(Messages || `[]`)' --output text)" 0
  confere "fila de mortas vazia" \
    "$(aws_sim sqs get-queue-attributes --queue-url "$mortas" --attribute-names ApproximateNumberOfMessages \
      --query Attributes.ApproximateNumberOfMessages --output text)" 0

  titulo "Trava de objeto: o relatório não se apaga antes do prazo"
  local versao
  versao=$(aws_sim s3api list-object-versions --bucket "$balde" --prefix conciliacao/ --query 'Versions[0].VersionId' --output text)
  recusa=$(aws_sim s3api delete-object --bucket "$balde" --key conciliacao/2026-10-30.csv --version-id "$versao" 2>&1 >/dev/null || true)
  confere "apagar a versão travada é recusado" "$(grep -o 'AccessDenied' <<<"$recusa" | head -1)" AccessDenied

  # Com a permissão especial do modo GOVERNANCE, sai. O Moto só deixa desligar a trava (o que o
  # destroy faz) com o balde vazio, por isso esvaziamos antes.
  local chave
  aws_sim s3api list-object-versions --bucket "$balde" --output json \
    --query 'Versions[].[Key,VersionId]' | python3 -c 'import json,sys; [print(k, v) for k, v in json.load(sys.stdin)]' |
    while read -r chave versao; do
      aws_sim s3api delete-object --bucket "$balde" --key "$chave" --version-id "$versao" --bypass-governance-retention >/dev/null
    done

  titulo "Destruindo tudo no simulador"
  t=$(agora)
  "${tf[@]}" destroy -auto-approve -no-color -var "endereco_simulador=$MOTO_ENDERECO" | grep -E '^Destroy complete'
  echo "(destroy em $(duracao "$t"))"
  confere "nenhum balde sobrou" \
    "$(aws_sim s3api list-buckets --query 'length(Buckets)' --output text)" 0

  [ "$FALHAS" -eq 0 ] || erro "$FALHAS conferência(s) falharam"
  titulo "SIMULAÇÃO OK em $(duracao "$inicio")"
}

# ---------------------------------------------------------------------------------------------
simular_tudo() {
  achar_terraform
  local dir="$TEMP/t/aws" inicio
  inicio=$(agora)
  trap 'derrubar_simulador; rm -rf "$TEMP"' EXIT

  titulo "EXPERIMENTO: a infraestrutura AWS inteira no simulador"
  # Sem isto o Moto não conhece as políticas gerenciadas da AWS, e o EKS nem começa.
  subir_simulador -e MOTO_IAM_LOAD_MANAGED_POLICIES=true
  copiar "$TEMP/t" simulador_override.tf
  "$TF" -chdir="$dir" init -backend=false -lockfile=readonly -no-color >/dev/null

  # O apply não para no primeiro erro: o Terraform segue criando o que não depende da peça que
  # falhou. As esperas do provedor são as da AWS (até dezenas de minutos para EKS e MSK).
  # O limite existe porque há peça que o Moto nunca conclui (a RedriveAllowPolicy, ADR-0040): com
  # SIGINT, o Terraform para de criar, espera o que está em curso e salva o estado.
  : "${SAIDA_TUDO:=$TEMP/tudo.txt}"
  timeout --signal=INT --kill-after=120 "${LIMITE_TUDO:-600}" "$TF" -chdir="$dir" apply -auto-approve -no-color -parallelism=20 \
    -var protecao_contra_exclusao=false >"$SAIDA_TUDO" 2>&1 || true

  local criados planejados
  criados=$("$TF" -chdir="$dir" state list 2>/dev/null | grep -vcE '(^|\.)data\.' || true)
  planejados=$(grep -oE 'Plan: [0-9]+' "$SAIDA_TUDO" | grep -oE '[0-9]+' || echo "?")
  titulo "Resultado"
  echo "Peças no plano: $planejados    criadas pelo simulador: $criados"
  echo
  echo "Recusadas (uma linha por erro):"
  grep -E '^Error:|with module\.' "$SAIDA_TUDO" | paste - - | sed 's/^/  /' | sort | uniq -c || true
  echo
  echo "Lembrete: o simulador aceitar NÃO prova que a AWS aceitaria."
  echo "(experimento em $(duracao "$inicio"); derrubar o simulador apaga tudo)"
}

case "${1:-}" in
  validar) validar ;;
  simular) simular ;;
  simular-tudo) simular_tudo ;;
  *)
    sed -n '2,15p' "$0"
    exit 1
    ;;
esac
