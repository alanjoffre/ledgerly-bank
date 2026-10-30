# A infraestrutura AWS do Ledgerly Bank, montada a partir dos módulos.
#
# NUNCA APLICADA numa conta real (ADR-0006 e ADR-0040): este código é validado no CI com
# `fmt`, `validate`, `plan` sem conta e varredura de segurança. O que cada módulo cria, quanto
# custaria e o que foi validado de verdade está em infra/terraform/README.md.

locals {
  nome = "ledgerly-${var.ambiente}"

  # Tudo que roda no Kubernetes e ganha grupo de segurança e papel IAM próprios. O gateway é uma
  # imagem só para os dois BFFs (internet banking e backoffice); a conciliação é um CronJob.
  servicos = ["gateway", "contas", "razao", "pix", "antifraude", "extrato", "assistente", "keycloak", "conciliacao"]

  # As imagens que o projeto constrói (o Keycloak usa a imagem oficial, já com o realm montado).
  imagens = ["gateway", "contas", "razao", "pix", "antifraude", "extrato", "assistente", "conciliacao"]

  # A porta de cada serviço, a mesma do chart (infra/k8s/ledgerly/values.yaml). A conciliação é um
  # CronJob: ninguém a chama, e ela não tem sonda.
  portas = {
    gateway    = [8080, 8090] # internet banking e backoffice
    keycloak   = [8080]
    contas     = [8081]
    razao      = [8082]
    pix        = [8083]
    extrato    = [8084]
    antifraude = [8085]
    assistente = [8086]
  }

  # A ServiceAccount de cada serviço no chart. A imagem do gateway é uma só, mas são dois Deployments.
  contas_de_servico = {
    gateway = ["gateway-ib", "gateway-bo"]
  }

  # Quem chama quem por HTTP (docs/arquitetura/visao-geral.md). Todo serviço chama o Keycloak:
  # para validar o token (as chaves públicas, JWKS) e para pedir o token de serviço (ADR-0027).
  chamadas = {
    gateway    = ["contas", "pix", "extrato", "antifraude", "assistente", "keycloak"]
    pix        = ["antifraude", "razao", "contas", "keycloak"]
    assistente = ["contas", "extrato", "keycloak"]
    contas     = ["razao", "keycloak"]
    razao      = ["keycloak"]
    extrato    = ["contas", "keycloak"]
    antifraude = ["keycloak"]
  }

  # Os tópicos de infra/docker/kafka/criar-topicos.sh: quem publica (Outbox) e quem consome.
  # Quem consome também escreve na fila de mensagens mortas (.dlt) do tópico que lê.
  kafka = {
    contas = { escreve = ["contas.eventos.v1"], le = [] }
    pix    = { escreve = ["pix.transferencias.v1"], le = [] }
    razao  = { escreve = ["razao.lancamentos.v1", "contas.eventos.v1.dlt"], le = ["contas.eventos.v1"] }
    extrato = {
      escreve = ["contas.eventos.v1.dlt", "razao.lancamentos.v1.dlt", "pix.transferencias.v1.dlt"]
      le      = ["contas.eventos.v1", "razao.lancamentos.v1", "pix.transferencias.v1"]
    }
  }

  # A conciliação lê o banco do pix e o do razão (ADR-0022) e grava o relatório no S3.
  acessos_extras = {
    pix   = ["conciliacao"]
    razao = ["conciliacao"]
  }

  # Senhas que não são de banco: administrador do Keycloak e o segredo de cada cliente OAuth2.
  segredos = [
    "keycloak/admin",
    "keycloak/cliente-gateway-ib",
    "keycloak/cliente-gateway-bo",
    "keycloak/cliente-pix",
    "keycloak/cliente-contas",
    "keycloak/cliente-extrato",
  ]
}

module "chaves" {
  source = "./modulos/chaves"
  nome   = local.nome
}

module "rede" {
  source = "./modulos/rede"

  nome               = local.nome
  regiao             = var.regiao
  cidr_vpc           = var.cidr_vpc
  zonas              = var.zonas
  nat_por_zona       = var.nat_por_zona
  dias_retencao_logs = var.dias_retencao_logs
}

module "eks" {
  source = "./modulos/eks"

  nome                   = local.nome
  versao_kubernetes      = var.versao_kubernetes
  subredes               = module.rede.subredes_aplicacao
  chave_kms_arn          = module.chaves.chave_arn
  cidrs_acesso_api       = var.cidrs_acesso_api
  tipos_de_no            = var.tipos_de_no
  nos_minimo             = var.nos_minimo
  nos_maximo             = var.nos_maximo
  papeis_administradores = var.papeis_administradores
  dias_retencao_logs     = var.dias_retencao_logs
}

module "seguranca" {
  source = "./modulos/seguranca"

  nome              = local.nome
  vpc_id            = module.rede.vpc_id
  cidr_vpc          = module.rede.cidr_vpc
  sg_cluster        = module.eks.sg_cluster
  servicos          = local.servicos
  chamadas          = local.chamadas
  servicos_publicos = ["gateway", "keycloak"]
  portas            = local.portas
  cidrs_entrada     = ["0.0.0.0/0"]
}

module "bancos" {
  source = "./modulos/bancos"

  nome                     = local.nome
  vpc_id                   = module.rede.vpc_id
  subredes                 = module.rede.subredes_dados
  chave_kms_arn            = module.chaves.chave_arn
  sg_servicos              = module.seguranca.sg_servicos
  versao_postgres          = var.versao_postgres
  bancos                   = var.bancos
  acessos_extras           = local.acessos_extras
  protecao_contra_exclusao = var.protecao_contra_exclusao
}

module "cache" {
  source = "./modulos/cache"

  nome                = local.nome
  vpc_id              = module.rede.vpc_id
  subredes            = module.rede.subredes_dados
  chave_kms_arn       = module.chaves.chave_arn
  sg_servicos         = module.seguranca.sg_servicos
  servicos_com_acesso = ["pix"]
  versao_valkey       = var.versao_valkey
  tipo_de_no          = var.tipo_de_no_cache
}

module "mensageria" {
  source = "./modulos/mensageria"

  nome                = local.nome
  vpc_id              = module.rede.vpc_id
  subredes            = module.rede.subredes_dados
  chave_kms_arn       = module.chaves.chave_arn
  sg_servicos         = module.seguranca.sg_servicos
  servicos_com_acesso = keys(local.kafka)
  versao_kafka        = var.versao_kafka
  tipo_de_broker      = var.tipo_de_broker
  disco_gb            = var.disco_kafka_gb
  dias_retencao_logs  = var.dias_retencao_logs
}

module "documentos" {
  source = "./modulos/documentos"

  nome                     = local.nome
  vpc_id                   = module.rede.vpc_id
  subredes                 = module.rede.subredes_dados
  chave_kms_arn            = module.chaves.chave_arn
  sg_servicos              = module.seguranca.sg_servicos
  servicos_com_acesso      = ["extrato"]
  versao_documentdb        = var.versao_documentdb
  classe                   = var.classe_documentdb
  instancias               = var.instancias_documentdb
  dias_de_backup           = 7
  protecao_contra_exclusao = var.protecao_contra_exclusao
}

module "registro" {
  source = "./modulos/registro"

  prefixo       = "ledgerly"
  servicos      = local.imagens
  chave_kms_arn = module.chaves.chave_arn
}

module "armazenamento" {
  source = "./modulos/armazenamento"

  nome           = local.nome
  chave_kms_arn  = module.chaves.chave_arn
  dias_de_guarda = var.dias_de_guarda_relatorios
}

module "segredos" {
  source = "./modulos/segredos"

  nome          = local.nome
  segredos      = local.segredos
  chave_kms_arn = module.chaves.chave_arn
}

module "identidades" {
  source = "./modulos/identidades"

  nome          = local.nome
  regiao        = var.regiao
  cluster       = module.eks.nome
  namespace     = "ledgerly"
  servicos      = local.servicos
  chave_kms_arn = module.chaves.chave_arn

  # Cada serviço lê só os próprios segredos.
  segredos_por_servico = {
    contas     = [module.segredos.arns["keycloak/cliente-contas"]]
    razao      = []
    pix        = [module.cache.segredo, module.segredos.arns["keycloak/cliente-pix"]]
    antifraude = []
    extrato    = [module.documentos.segredo, module.segredos.arns["keycloak/cliente-extrato"]]
    assistente = []
    gateway    = [module.segredos.arns["keycloak/cliente-gateway-ib"], module.segredos.arns["keycloak/cliente-gateway-bo"]]
    # O Keycloak entra no próprio banco com o usuário mestre (o RDS gera e gira a senha).
    keycloak = [module.segredos.arns["keycloak/admin"], module.bancos.segredos["keycloak"]]
  }

  contas_de_servico   = local.contas_de_servico
  bancos_ids          = module.bancos.ids_de_recurso
  bancos_arns         = module.bancos.arns
  acessos_extras      = local.acessos_extras
  kafka_arn           = module.mensageria.arn
  kafka               = local.kafka
  balde_arn           = module.armazenamento.balde_arn
  escrevem_relatorios = ["conciliacao"]
}
