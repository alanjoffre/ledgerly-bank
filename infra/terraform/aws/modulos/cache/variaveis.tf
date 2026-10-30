variable "nome" {
  description = "Prefixo dos nomes (ex.: ledgerly-producao)."
  type        = string
}

variable "vpc_id" {
  description = "Id da VPC."
  type        = string
}

variable "subredes" {
  description = "Sub-redes de dados."
  type        = list(string)
}

variable "chave_kms_arn" {
  description = "Chave KMS que cifra o cache e o segredo da senha."
  type        = string
}

variable "sg_servicos" {
  description = "Grupo de segurança de cada serviço."
  type        = map(string)
}

variable "servicos_com_acesso" {
  description = "Serviços que falam com o cache (hoje, só o pix)."
  type        = list(string)
}

variable "versao_valkey" {
  description = "Versão do Valkey no ElastiCache (ex.: 9.1)."
  type        = string
}

variable "tipo_de_no" {
  description = "Tipo de máquina de cada nó do cache."
  type        = string
}

variable "versao_da_senha" {
  description = "Suba este número para gerar e aplicar uma senha nova no cache e no segredo."
  type        = number
  default     = 1
}
