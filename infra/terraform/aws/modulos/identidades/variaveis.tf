variable "nome" {
  description = "Prefixo dos nomes (ex.: ledgerly-producao)."
  type        = string
}

variable "regiao" {
  description = "Região da AWS (entra no ARN do rds-db:connect)."
  type        = string
}

variable "cluster" {
  description = "Nome do cluster EKS."
  type        = string
}

variable "namespace" {
  description = "Namespace do Kubernetes onde rodam os serviços do banco."
  type        = string
}

variable "servicos" {
  description = "Serviços que recebem um papel IAM próprio."
  type        = list(string)
}

variable "chave_kms_arn" {
  description = "Chave KMS dos dados (para decifrar segredos e cifrar relatórios)."
  type        = string
}

variable "segredos_por_servico" {
  description = "Segredos que cada serviço pode ler (mapa serviço => lista de ARNs)."
  type        = map(list(string))
}

variable "bancos_ids" {
  description = "Id de recurso do banco de cada serviço."
  type        = map(string)
}

variable "bancos_arns" {
  description = "ARN do banco de cada serviço (de onde sai o número da conta)."
  type        = map(string)
}

variable "kafka_arn" {
  description = "ARN do cluster MSK."
  type        = string
}

variable "kafka" {
  description = "Tópicos que cada serviço escreve e lê."
  type = map(object({
    escreve = list(string)
    le      = list(string)
  }))
}

variable "balde_arn" {
  description = "ARN do balde dos relatórios."
  type        = string
}

variable "escrevem_relatorios" {
  description = "Serviços que gravam relatórios no S3."
  type        = list(string)
}

variable "acessos_extras" {
  description = "Quem mais entra em cada banco além do dono (mapa banco => lista de serviços)."
  type        = map(list(string))
  default     = {}
}

variable "contas_de_servico" {
  description = "ServiceAccounts de quem não usa o próprio nome: mapa serviço => nomes no chart."
  type        = map(list(string))
  default     = {}
}
