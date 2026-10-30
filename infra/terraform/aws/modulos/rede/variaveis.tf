variable "nome" {
  description = "Prefixo dos nomes (ex.: ledgerly-producao)."
  type        = string
}

variable "regiao" {
  description = "Região da AWS (ex.: sa-east-1), usada no nome do ponto de acesso ao S3."
  type        = string
}

variable "cidr_vpc" {
  description = "Faixa de IPs da VPC. Precisa ser /16 para caber as três camadas de sub-rede."
  type        = string

  validation {
    condition     = can(cidrhost(var.cidr_vpc, 0)) && endswith(var.cidr_vpc, "/16")
    error_message = "Informe uma faixa /16 válida (ex.: 10.40.0.0/16)."
  }
}

variable "zonas" {
  description = "Zonas de disponibilidade (2 ou 3). Vêm por variável, e não consultadas na AWS, para o plan rodar sem conta."
  type        = list(string)

  validation {
    condition     = length(var.zonas) >= 2 && length(var.zonas) <= 3
    error_message = "Use 2 ou 3 zonas: uma só não sobrevive à queda de um prédio da AWS."
  }
}

variable "nat_por_zona" {
  description = "true: um NAT por zona (sobrevive à queda de uma zona, custa mais). false: um NAT só."
  type        = bool
}

variable "dias_retencao_logs" {
  description = "Dias que o registro de fluxo da rede fica guardado."
  type        = number
}
