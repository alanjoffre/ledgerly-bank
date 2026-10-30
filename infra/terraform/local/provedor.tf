# O provedor da AWS apontado para o SIMULADOR (Moto, ADR-0040), não para a AWS.
#
# Moto é um programa de código aberto que imita a API da AWS na sua máquina: responde aos mesmos
# pedidos (criar balde, criar fila, guardar segredo) e guarda tudo na memória. Nada sai do
# computador, nada é cobrado. As credenciais "test"/"test" são falsas: o Moto aceita qualquer uma.
#
# O endereço de cada serviço (endpoints) troca o "s3.sa-east-1.amazonaws.com" pelo simulador.

provider "aws" {
  region     = "sa-east-1"
  access_key = "test"
  secret_key = "test"

  # Não procurar credencial nem conta de verdade: a conta do Moto é a 123456789012, de mentira.
  skip_credentials_validation = true
  skip_requesting_account_id  = true
  skip_metadata_api_check     = true

  # O S3 no simulador atende por caminho (http://localhost:15000/balde), não por subdomínio
  # (http://balde.s3.amazonaws.com), que precisaria de DNS.
  s3_use_path_style = true

  endpoints {
    kms            = var.endereco_simulador
    s3             = var.endereco_simulador
    sqs            = var.endereco_simulador
    secretsmanager = var.endereco_simulador
    sts            = var.endereco_simulador
    iam            = var.endereco_simulador
  }

  default_tags {
    tags = {
      Projeto       = "ledgerly-bank"
      Ambiente      = "simulador"
      GerenciadoPor = "terraform"
    }
  }
}
