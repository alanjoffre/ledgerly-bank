variable "nome" {
  description = "Prefixo dos nomes (ex.: ledgerly-producao)."
  type        = string
}

variable "vpc_id" {
  description = "Id da VPC."
  type        = string
}

variable "subredes" {
  description = "Sub-redes de dados: um broker em cada uma."
  type        = list(string)
}

variable "chave_kms_arn" {
  description = "Chave KMS que cifra os discos dos brokers."
  type        = string
}

variable "sg_servicos" {
  description = "Grupo de segurança de cada serviço."
  type        = map(string)
}

variable "servicos_com_acesso" {
  description = "Serviços que publicam ou leem eventos."
  type        = list(string)
}

variable "versao_kafka" {
  description = "Versão do Kafka no MSK, no formato da AWS (ex.: 4.1.x)."
  type        = string
}

variable "tipo_de_broker" {
  description = "Tipo de máquina de cada broker."
  type        = string
}

variable "disco_gb" {
  description = "Disco de cada broker, em GB."
  type        = number
}

variable "dias_retencao_logs" {
  description = "Dias que os registros dos brokers ficam guardados."
  type        = number
}
