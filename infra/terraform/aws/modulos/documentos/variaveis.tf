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
  description = "Chave KMS que cifra o cluster e o segredo do usuário mestre."
  type        = string
}

variable "sg_servicos" {
  description = "Grupo de segurança de cada serviço."
  type        = map(string)
}

variable "servicos_com_acesso" {
  description = "Serviços que falam com o DocumentDB (hoje, só o extrato)."
  type        = list(string)
}

variable "versao_documentdb" {
  description = "Versão do motor do DocumentDB (ex.: 8.0.0, compatível com a API do MongoDB 8.0)."
  type        = string
}

variable "classe" {
  description = "Tipo de máquina de cada instância do DocumentDB."
  type        = string
}

variable "instancias" {
  description = "Quantas instâncias (1 principal + réplicas de leitura em outras zonas)."
  type        = number
}

variable "dias_de_backup" {
  description = "Dias de cópia automática."
  type        = number
}

variable "protecao_contra_exclusao" {
  description = "true: a AWS recusa apagar o cluster até a proteção ser desligada."
  type        = bool
}
