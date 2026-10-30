# As mesmas versões fixas da raiz da AWS (infra/terraform/aws/versions.tf): o simulador testa o
# mesmo provedor que um dia falaria com a conta de verdade.
#
# Sem estado remoto: o estado fica num arquivo local, apagado pelo scripts/nuvem.sh ao final.

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
}
