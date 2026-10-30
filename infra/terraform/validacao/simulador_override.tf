# EXPERIMENTO: a infraestrutura AWS INTEIRA contra o simulador (Moto), com `scripts/nuvem.sh
# simular-tudo`. Não faz parte do CI. Serve para ver quais peças o simulador aceita criar.
#
# Mesmo truque do sem_conta_override.tf: o script copia o código para uma pasta temporária e
# põe este arquivo ao lado. Aqui, além de credencial falsa, cada serviço aponta para o Moto.
#
# Cuidado ao ler o resultado: o Moto aceitar não prova que a AWS aceitaria. Ele imita a API, não
# as regras de negócio dela (cota, combinação de versão e tipo de máquina, permissão do KMS...).

provider "aws" {
  access_key                  = "test"
  secret_key                  = "test"
  skip_credentials_validation = true
  skip_requesting_account_id  = true
  skip_metadata_api_check     = true
  s3_use_path_style           = true

  endpoints {
    docdb          = "http://localhost:15000"
    ec2            = "http://localhost:15000"
    ecr            = "http://localhost:15000"
    eks            = "http://localhost:15000"
    elasticache    = "http://localhost:15000"
    iam            = "http://localhost:15000"
    kafka          = "http://localhost:15000"
    kms            = "http://localhost:15000"
    logs           = "http://localhost:15000"
    rds            = "http://localhost:15000"
    s3             = "http://localhost:15000"
    secretsmanager = "http://localhost:15000"
    sqs            = "http://localhost:15000"
    sts            = "http://localhost:15000"
  }
}
