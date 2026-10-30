# Módulo registro: um repositório de imagens (ECR) por serviço.
#
# ECR (*Elastic Container Registry*) guarda as imagens Docker que o EKS baixa. Um repositório por
# serviço deixa a permissão e a limpeza separadas, e três regras valem para todos:
# - etiqueta imutável (*immutable tags*): a imagem "pix:1.4.0" de hoje é a mesma de amanhã; ninguém
#   sobrescreve uma versão que está em produção;
# - varredura de vulnerabilidade a cada envio (*scan on push*);
# - limpeza automática: ficam as últimas N imagens, para o armazenamento não crescer sem fim.

resource "aws_ecr_repository" "servico" {
  for_each = toset(var.servicos)

  name                 = "${var.prefixo}/${each.key}"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "KMS"
    kms_key         = var.chave_kms_arn
  }

  tags = { Servico = each.key }
}

resource "aws_ecr_lifecycle_policy" "servico" {
  for_each   = aws_ecr_repository.servico
  repository = each.value.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Apaga imagem sem etiqueta depois de 7 dias"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 7
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Guarda as ultimas ${var.imagens_guardadas} imagens"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = var.imagens_guardadas
        }
        action = { type = "expire" }
      },
    ]
  })
}

variable "prefixo" {
  description = "Prefixo dos repositórios (ex.: ledgerly → ledgerly/pix)."
  type        = string
}

variable "servicos" {
  description = "Um repositório para cada serviço que vira imagem."
  type        = list(string)
}

variable "chave_kms_arn" {
  description = "Chave KMS que cifra as imagens."
  type        = string
}

variable "imagens_guardadas" {
  description = "Quantas imagens com etiqueta ficam em cada repositório."
  type        = number
  default     = 30

  validation {
    condition     = var.imagens_guardadas >= 5
    error_message = "Guarde pelo menos 5 imagens, para ter para onde voltar (rollback)."
  }
}

output "enderecos" {
  description = "Endereço de cada repositório (mapa serviço => URL)."
  value       = { for s, r in aws_ecr_repository.servico : s => r.repository_url }
}
