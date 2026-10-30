variable "nome" {
  description = "Prefixo dos nomes (ex.: ledgerly-producao)."
  type        = string
}

variable "chave_kms_arn" {
  description = "Chave KMS que cifra os relatórios."
  type        = string
}

variable "trava_de_objeto" {
  description = "Liga a trava de objeto (Object Lock): relatório não se apaga antes do prazo. Só se escolhe ao criar o balde."
  type        = bool
  default     = true
}

variable "dias_de_guarda" {
  description = "Dias que cada relatório fica travado contra exclusão (e que a versão antiga fica guardada)."
  type        = number
  default     = 1825 # 5 anos

  validation {
    condition     = var.dias_de_guarda >= 1 && var.dias_de_guarda <= 3650
    error_message = "Escolha de 1 a 3650 dias (10 anos)."
  }
}

variable "apagar_com_conteudo" {
  description = "true: o destroy apaga o balde mesmo com relatórios dentro. Só para o simulador local."
  type        = bool
  default     = false
}

variable "restringir_fila_de_mortas" {
  description = "true: só a fila dos relatórios pode mandar mensagem para a fila de mortas (RedriveAllowPolicy). false só no simulador, que não guarda essa regra."
  type        = bool
  default     = true
}
