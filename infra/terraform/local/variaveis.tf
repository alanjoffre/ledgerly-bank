variable "endereco_simulador" {
  description = "Endereço do Moto (o simulador da AWS). O scripts/nuvem.sh sobe o Moto nesta porta."
  type        = string
  default     = "http://localhost:15000"

  validation {
    condition     = can(regex("^http://(localhost|127\\.0\\.0\\.1|[a-z0-9-]+):[0-9]+$", var.endereco_simulador))
    error_message = "Este código só fala com um simulador local (http://localhost:<porta>), nunca com a AWS."
  }
}

variable "trava_de_objeto" {
  description = "Liga a trava de objeto (Object Lock) no balde, como na AWS."
  type        = bool
  default     = true
}
