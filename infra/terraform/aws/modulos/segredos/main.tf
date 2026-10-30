# Módulo segredos: as senhas que não são de banco de dados, geradas e guardadas no Secrets Manager.
#
# São as senhas do Keycloak: a do administrador e o segredo de cada cliente OAuth2 (os dois BFFs
# e os serviços que pedem token de serviço, ADR-0027). Nenhuma está no código nem no estado do
# Terraform: o gerador é efêmero (*ephemeral*, some ao fim do comando) e o Secrets Manager recebe
# o valor por um campo só escrita (*write-only*). O pod lê o segredo na subida, pelo papel IAM
# dele (módulo identidades), e só o próprio segredo.
#
# Trocar uma senha: subir o número em `versoes` para aquele segredo e rodar o apply.
#
# Este módulo também roda DE VERDADE no simulador local (infra/terraform/local).

ephemeral "random_password" "senha" {
  for_each = toset(var.segredos)

  length  = 40
  special = false
}

resource "aws_secretsmanager_secret" "senha" {
  for_each = toset(var.segredos)

  name                    = "${var.nome}/${each.key}"
  description             = "Ledgerly: ${each.key} (gerado pelo Terraform, nunca guardado no estado)"
  kms_key_id              = var.chave_kms_arn
  recovery_window_in_days = var.dias_para_recuperar
}

resource "aws_secretsmanager_secret_version" "senha" {
  for_each = toset(var.segredos)

  secret_id                = aws_secretsmanager_secret.senha[each.key].id
  secret_string_wo         = ephemeral.random_password.senha[each.key].result
  secret_string_wo_version = lookup(var.versoes, each.key, 1)
}

variable "nome" {
  description = "Prefixo dos nomes (ex.: ledgerly-producao)."
  type        = string
}

variable "segredos" {
  description = "Nomes dos segredos a gerar (ex.: keycloak/admin)."
  type        = list(string)

  validation {
    condition     = alltrue([for s in var.segredos : can(regex("^[a-z0-9/_-]+$", s))])
    error_message = "Use só letras minúsculas, números, barra, hífen e sublinhado no nome do segredo."
  }
}

variable "versoes" {
  description = "Versão de cada segredo (mapa nome => número). Subir o número gera uma senha nova."
  type        = map(number)
  default     = {}
}

variable "chave_kms_arn" {
  description = "Chave KMS que cifra os segredos."
  type        = string
}

variable "dias_para_recuperar" {
  description = "Dias em que um segredo apagado ainda pode ser recuperado (0 = apaga na hora, só no simulador; 7 a 30 na AWS)."
  type        = number
  default     = 30

  validation {
    condition     = var.dias_para_recuperar == 0 || (var.dias_para_recuperar >= 7 && var.dias_para_recuperar <= 30)
    error_message = "A AWS aceita 0 (apagar na hora) ou de 7 a 30 dias."
  }
}

output "arns" {
  description = "ARN de cada segredo (mapa nome => ARN)."
  value       = { for s, x in aws_secretsmanager_secret.senha : s => x.arn }
}
