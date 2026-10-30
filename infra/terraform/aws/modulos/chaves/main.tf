# Módulo chaves: a chave de criptografia do banco (KMS).
#
# KMS (*Key Management Service*) guarda chaves de criptografia que nunca saem da AWS: os serviços
# (RDS, S3, MSK...) pedem ao KMS para cifrar e decifrar, e cada pedido fica registrado. Uma chave
# gerenciada por nós (*customer managed key*), e não a padrão da AWS, permite três coisas que um
# banco precisa: girar a chave todo ano, revogar o acesso de um serviço e auditar quem usou.
#
# Uma chave só para tudo é uma simplificação consciente: um banco de verdade separa por domínio
# (uma para o razão, outra para os relatórios), para que um vazamento de permissão não abra tudo.

variable "nome" {
  description = "Prefixo dos nomes (ex.: ledgerly-producao)."
  type        = string
}

variable "dias_para_apagar" {
  description = "Dias de espera antes de a chave ser apagada de vez (7 a 30). A espera é a rede de proteção: dá para cancelar."
  type        = number
  default     = 30

  validation {
    condition     = var.dias_para_apagar >= 7 && var.dias_para_apagar <= 30
    error_message = "A AWS aceita de 7 a 30 dias de espera."
  }
}

resource "aws_kms_key" "dados" {
  description             = "Ledgerly Bank: cifra os dados em repouso (bancos, arquivos, segredos)"
  enable_key_rotation     = true
  rotation_period_in_days = 365
  deletion_window_in_days = var.dias_para_apagar

  tags = { Name = "${var.nome}-dados" }
}

resource "aws_kms_alias" "dados" {
  name          = "alias/${var.nome}-dados"
  target_key_id = aws_kms_key.dados.key_id
}

output "chave_arn" {
  description = "ARN da chave KMS dos dados."
  value       = aws_kms_key.dados.arn
}

output "chave_id" {
  description = "Id da chave KMS dos dados."
  value       = aws_kms_key.dados.key_id
}
