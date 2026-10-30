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
  description = "Chave KMS que cifra discos, cópias, Performance Insights e o segredo do usuário mestre."
  type        = string
}

variable "sg_servicos" {
  description = "Grupo de segurança de cada serviço (o único que entra no banco do mesmo nome)."
  type        = map(string)
}

variable "versao_postgres" {
  description = "Versão do PostgreSQL no RDS (ex.: 18.6)."
  type        = string
}

variable "bancos" {
  description = "Um banco por serviço: tamanho da máquina, réplica em outra zona, disco e dias de backup."
  type = map(object({
    classe           = string
    multi_az         = bool
    armazenamento_gb = number
    dias_de_backup   = number
  }))
}

variable "protecao_contra_exclusao" {
  description = "true: a AWS recusa apagar o banco até a proteção ser desligada."
  type        = bool
}

variable "acessos_extras" {
  description = "Quem mais entra em cada banco além do dono (mapa banco => lista de serviços)."
  type        = map(list(string))
  default     = {}
}
