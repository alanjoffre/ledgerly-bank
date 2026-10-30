# SÓ PARA VALIDAR SEM CONTA. Não fica em infra/terraform/aws: o scripts/nuvem.sh copia o código
# para uma pasta temporária e põe este arquivo ao lado.
#
# Arquivo *_override.tf é um recurso do Terraform: os blocos dele se misturam aos de mesmo nome
# (aqui, o provider "aws"), sem mexer no arquivo original. As opções abaixo dizem ao provedor
# para não procurar credenciais nem perguntar o número da conta — o `plan` então calcula tudo
# sem falar com a AWS. As credenciais são falsas de propósito: "test" não abre conta nenhuma.

provider "aws" {
  access_key                  = "test"
  secret_key                  = "test"
  skip_credentials_validation = true
  skip_requesting_account_id  = true
  skip_metadata_api_check     = true
}
