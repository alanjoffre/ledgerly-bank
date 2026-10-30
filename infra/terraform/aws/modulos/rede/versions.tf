# Versões mínimas que este módulo aceita. A versão EXATA fica na raiz (aws/versions.tf); aqui é
# faixa, como se faz em módulo reutilizável: 1.11 é a primeira com senha "só de escrita"
# (*write-only*, que não vai para o estado) e a 6.x do provedor é a que este código conhece.
terraform {
  required_version = ">= 1.11"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.0, < 7.0"
    }
  }
}
