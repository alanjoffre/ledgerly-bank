variable "nome" {
  description = "Nome do cluster (ex.: ledgerly-producao)."
  type        = string
}

variable "versao_kubernetes" {
  description = "Versão do Kubernetes no EKS (ex.: 1.37)."
  type        = string
}

variable "subredes" {
  description = "Sub-redes da aplicação, onde ficam os nós e as placas de rede do plano de controle."
  type        = list(string)
}

variable "chave_kms_arn" {
  description = "Chave KMS que cifra os segredos do Kubernetes."
  type        = string
}

variable "cidrs_acesso_api" {
  description = "Faixas de IP que alcançam a API do Kubernetes pela internet. Lista vazia: só por dentro da VPC."
  type        = list(string)
}

variable "tipos_de_no" {
  description = "Tipos de máquina EC2 dos nós."
  type        = list(string)
}

variable "nos_minimo" {
  description = "Mínimo de nós (também o número inicial)."
  type        = number
}

variable "nos_maximo" {
  description = "Máximo de nós que o autoescalador pode criar."
  type        = number
}

variable "papeis_administradores" {
  description = "ARNs de papéis IAM que administram o cluster."
  type        = list(string)
}

variable "dias_retencao_logs" {
  description = "Dias que os registros do plano de controle ficam guardados."
  type        = number
}
