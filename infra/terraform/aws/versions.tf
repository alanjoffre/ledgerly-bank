# Versões fixas: o Terraform e os provedores (*providers*, os plugins que falam com cada nuvem)
# são exatamente estes. Versão "parecida" muda o plano sem ninguém ter mudado o código.
# O arquivo .terraform.lock.hcl, ao lado, guarda o hash de cada provedor baixado.

terraform {
  required_version = "1.17.1"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "6.71.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "3.9.1"
    }
  }

  # Estado remoto (*remote state*): DESLIGADO de propósito (ADR-0006, custo zero).
  #
  # O estado é o arquivo em que o Terraform anota o que criou. Numa conta de verdade ele mora num
  # balde S3 cifrado e versionado, com trava (*lock*) para duas pessoas não aplicarem ao mesmo
  # tempo — desde o Terraform 1.10 a trava é um arquivo no próprio S3 (use_lockfile), sem a
  # tabela do DynamoDB de antes. Ligar exige criar o balde antes (fora deste código, porque o
  # código não pode guardar o próprio estado num balde que ele mesmo cria) e tirar o comentário:
  #
  # backend "s3" {
  #   bucket       = "ledgerly-estado-terraform"
  #   key          = "producao/terraform.tfstate"
  #   region       = "sa-east-1"
  #   encrypt      = true
  #   use_lockfile = true
  # }
}
