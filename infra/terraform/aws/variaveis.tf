# As escolhas de quem aplica. Todas têm valor padrão, para o `plan` rodar sem nenhum arquivo extra;
# um ambiente de verdade sobrescreve o que quiser num arquivo .tfvars.

variable "regiao" {
  description = "Região da AWS. São Paulo (sa-east-1): o dado do cliente brasileiro fica no Brasil."
  type        = string
  default     = "sa-east-1"

  validation {
    condition     = can(regex("^[a-z]{2}-[a-z]+-[0-9]$", var.regiao))
    error_message = "Use o código da região da AWS, como sa-east-1 ou us-east-1."
  }
}

variable "zonas" {
  description = "Zonas de disponibilidade usadas (2 ou 3), todas da região escolhida."
  type        = list(string)
  default     = ["sa-east-1a", "sa-east-1b", "sa-east-1c"]

  validation {
    condition     = length(var.zonas) >= 2 && length(var.zonas) <= 3 && length(distinct(var.zonas)) == length(var.zonas)
    error_message = "Use 2 ou 3 zonas diferentes."
  }
}

variable "ambiente" {
  description = "Nome do ambiente: entra no nome de cada peça e nas etiquetas."
  type        = string
  default     = "producao"

  validation {
    condition     = contains(["desenvolvimento", "homologacao", "producao"], var.ambiente)
    error_message = "Ambiente deve ser desenvolvimento, homologacao ou producao."
  }
}

variable "cidr_vpc" {
  description = "Faixa de IPs privados da VPC (/16)."
  type        = string
  default     = "10.40.0.0/16"

  validation {
    condition     = can(cidrhost(var.cidr_vpc, 0)) && endswith(var.cidr_vpc, "/16")
    error_message = "Informe uma faixa /16 válida, como 10.40.0.0/16."
  }
}

variable "nat_por_zona" {
  description = "true: um NAT por zona (aguenta a queda de uma zona). false: um só (cerca de US$ 70/mês a menos por NAT)."
  type        = bool
  default     = true
}

variable "cidrs_acesso_api" {
  description = "Faixas de IP da equipe que alcançam a API do Kubernetes pela internet. Vazio: só por dentro da VPC (VPN)."
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for c in var.cidrs_acesso_api : can(cidrhost(c, 0)) && !endswith(c, "/0")])
    error_message = "A API do Kubernetes não abre para a internet inteira (/0): informe as faixas da equipe."
  }
}

variable "papeis_administradores" {
  description = "ARNs dos papéis IAM que administram o cluster (ex.: o papel de plantão)."
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for p in var.papeis_administradores : can(regex("^arn:aws:iam::[0-9]{12}:role/", p))])
    error_message = "Informe ARNs de papel IAM (arn:aws:iam::<conta>:role/<nome>)."
  }
}

variable "versao_kubernetes" {
  description = "Versão do Kubernetes no EKS. 1.37 é a mais nova em suporte padrão em 30/10/2026."
  type        = string
  default     = "1.37"

  validation {
    condition     = can(regex("^1\\.[0-9]{2}$", var.versao_kubernetes))
    error_message = "Use o formato 1.NN (ex.: 1.37)."
  }
}

variable "tipos_de_no" {
  description = "Tipos de máquina EC2 dos nós do Kubernetes."
  type        = list(string)
  default     = ["m7i.large"]
}

variable "nos_minimo" {
  description = "Mínimo de nós do Kubernetes (um por zona, para sobreviver à queda de uma)."
  type        = number
  default     = 3

  validation {
    condition     = var.nos_minimo >= 2
    error_message = "Com menos de 2 nós, a troca de um nó derruba os serviços."
  }
}

variable "nos_maximo" {
  description = "Máximo de nós que o autoescalador pode criar."
  type        = number
  default     = 6
}

variable "versao_postgres" {
  description = "Versão do PostgreSQL no RDS (a mesma do compose local: 18.6)."
  type        = string
  default     = "18.6"

  validation {
    condition     = can(regex("^18\\.[0-9]+$", var.versao_postgres))
    error_message = "O grupo de parâmetros é da família postgres18: use 18.N."
  }
}

variable "bancos" {
  description = "Um RDS PostgreSQL por serviço. O núcleo do dinheiro (razão, contas, pix) tem réplica em outra zona e 35 dias de backup."
  type = map(object({
    classe           = string
    multi_az         = bool
    armazenamento_gb = number
    dias_de_backup   = number
  }))
  default = {
    razao      = { classe = "db.m7g.large", multi_az = true, armazenamento_gb = 100, dias_de_backup = 35 }
    contas     = { classe = "db.t4g.medium", multi_az = true, armazenamento_gb = 50, dias_de_backup = 35 }
    pix        = { classe = "db.t4g.medium", multi_az = true, armazenamento_gb = 50, dias_de_backup = 35 }
    antifraude = { classe = "db.t4g.small", multi_az = false, armazenamento_gb = 20, dias_de_backup = 7 }
    assistente = { classe = "db.t4g.small", multi_az = false, armazenamento_gb = 20, dias_de_backup = 7 }
    keycloak   = { classe = "db.t4g.small", multi_az = true, armazenamento_gb = 20, dias_de_backup = 7 }
  }

  validation {
    condition = alltrue([
      for b in values(var.bancos) :
      startswith(b.classe, "db.") && b.armazenamento_gb >= 20 && b.dias_de_backup >= 1 && b.dias_de_backup <= 35
    ])
    error_message = "Cada banco precisa de classe db.*, pelo menos 20 GB e de 1 a 35 dias de backup (o máximo do RDS)."
  }

  validation {
    condition     = alltrue([for s in ["razao", "contas", "pix"] : try(var.bancos[s].multi_az, false)])
    error_message = "Razão, contas e pix guardam dinheiro: precisam de réplica em outra zona (multi_az = true)."
  }
}

variable "versao_valkey" {
  description = "Versão do Valkey no ElastiCache."
  type        = string
  default     = "9.1"
}

variable "tipo_de_no_cache" {
  description = "Tipo de máquina de cada nó do cache."
  type        = string
  default     = "cache.t4g.small"
}

variable "versao_kafka" {
  description = "Versão do Kafka no MSK provisionado (4.1.x; a 4.2.x só existe nos brokers Express)."
  type        = string
  default     = "4.1.x"
}

variable "tipo_de_broker" {
  description = "Tipo de máquina de cada broker do Kafka."
  type        = string
  default     = "kafka.m7g.large"
}

variable "disco_kafka_gb" {
  description = "Disco de cada broker do Kafka, em GB."
  type        = number
  default     = 100
}

variable "versao_documentdb" {
  description = "Versão do DocumentDB (8.0.0 fala a API do MongoDB 8.0)."
  type        = string
  default     = "8.0.0"
}

variable "classe_documentdb" {
  description = "Tipo de máquina de cada instância do DocumentDB."
  type        = string
  default     = "db.t4g.medium"
}

variable "instancias_documentdb" {
  description = "Instâncias do DocumentDB (2 = principal + réplica em outra zona)."
  type        = number
  default     = 2

  validation {
    condition     = var.instancias_documentdb >= 1 && var.instancias_documentdb <= 16
    error_message = "O DocumentDB aceita de 1 a 16 instâncias por cluster."
  }
}

variable "dias_retencao_logs" {
  description = "Dias que os registros (rede, Kubernetes, Kafka) ficam no CloudWatch."
  type        = number
  default     = 90

  validation {
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653], var.dias_retencao_logs)
    error_message = "O CloudWatch só aceita estes prazos: 1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288 ou 3653 dias."
  }
}

variable "dias_de_guarda_relatorios" {
  description = "Dias que cada relatório da conciliação fica travado contra exclusão."
  type        = number
  default     = 1825
}

variable "protecao_contra_exclusao" {
  description = "true: bancos de dados recusam o destroy até a proteção ser desligada."
  type        = bool
  default     = true
}
