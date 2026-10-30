# O provedor da AWS, como seria numa conta de verdade: região e etiquetas (*tags*) padrão.
#
# Não há chave de acesso aqui, nunca: numa conta real as credenciais vêm do ambiente (papel IAM
# do CI por OIDC, ou o `aws sso login` de quem aplica). Para validar SEM conta, o script
# scripts/nuvem.sh copia este código para uma pasta temporária junto com
# infra/terraform/validacao/sem_conta_override.tf, que liga as opções de "não conferir a conta"
# só lá. Este arquivo continua limpo.

provider "aws" {
  region = var.regiao

  # Toda peça criada leva estas etiquetas: dá para achar, filtrar a conta e saber de quem é.
  default_tags {
    tags = {
      Projeto       = "ledgerly-bank"
      Ambiente      = var.ambiente
      GerenciadoPor = "terraform"
      Repositorio   = "github.com/alanjoffre/ledgerly-bank"
      CentroDeCusto = "estudo"
    }
  }
}
