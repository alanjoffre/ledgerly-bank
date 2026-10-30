variable "nome" {
  description = "Prefixo dos nomes (ex.: ledgerly-producao)."
  type        = string
}

variable "vpc_id" {
  description = "Id da VPC."
  type        = string
}

variable "cidr_vpc" {
  description = "Faixa de IPs da VPC (destino da saída interna)."
  type        = string
}

variable "sg_cluster" {
  description = "Grupo de segurança do cluster EKS (de onde vêm as sondas do kubelet)."
  type        = string
}

variable "servicos" {
  description = "Serviços que ganham um grupo de segurança próprio."
  type        = list(string)
}

variable "chamadas" {
  description = "Quem chama quem por HTTP: mapa origem => lista de destinos."
  type        = map(list(string))

  validation {
    condition = alltrue(flatten([
      for origem, destinos in var.chamadas : [for d in destinos : d != origem]
    ]))
    error_message = "Um serviço não precisa de regra para chamar a si mesmo."
  }
}

variable "servicos_publicos" {
  description = "Serviços que recebem tráfego do balanceador de carga (os BFFs e o login)."
  type        = list(string)
}

variable "portas" {
  description = "Portas HTTP de cada serviço dentro do Kubernetes: mapa serviço => portas."
  type        = map(list(number))
}

variable "cidrs_entrada" {
  description = "De onde o balanceador de carga aceita HTTPS."
  type        = list(string)
}
