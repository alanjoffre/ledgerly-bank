# Um pedaço da infraestrutura AWS que roda DE VERDADE, só que no simulador (Moto).
#
# Não é uma cópia: chama os MESMOS módulos de infra/terraform/aws/modulos. Se alguém quebrar o
# módulo de armazenamento ou o de segredos, o `scripts/nuvem.sh simular` quebra junto.
#
# O que sobe (o fluxo do relatório da conciliação, ADR-0022):
#   - uma chave KMS (módulo chaves);
#   - o balde S3 dos relatórios: versionado, cifrado com a chave, sem acesso público, com trava
#     de objeto e regra de ciclo de vida (módulo armazenamento);
#   - a fila SQS que recebe o aviso "relatório novo" do S3 e a fila de mensagens mortas (DLQ,
#     *dead-letter queue*), para onde vai o aviso que falhou 5 vezes (módulo armazenamento);
#   - dois segredos no Secrets Manager, com senha gerada e nunca escrita em arquivo (módulo segredos).
#
# Diferenças em relação à AWS (só aqui): o balde aceita `destroy` com conteúdo e os segredos
# somem na hora, para o simulador sempre poder ser zerado; e a regra "só a fila dos relatórios
# usa a fila de mortas" fica desligada, porque o Moto não a guarda (ADR-0040).

module "chaves" {
  source = "../aws/modulos/chaves"

  nome             = "ledgerly-simulador"
  dias_para_apagar = 7
}

module "armazenamento" {
  source = "../aws/modulos/armazenamento"

  nome                = "ledgerly-simulador"
  chave_kms_arn       = module.chaves.chave_arn
  trava_de_objeto     = var.trava_de_objeto
  dias_de_guarda      = 1
  apagar_com_conteudo = true

  # O Moto 5.2.4 não guarda a RedriveAllowPolicy da fila (responde "Unknown Attribute") e o
  # provedor espera por ela sem fim. Na AWS fica ligada (o padrão do módulo).
  restringir_fila_de_mortas = false
}

module "segredos" {
  source = "../aws/modulos/segredos"

  nome                = "ledgerly-simulador"
  segredos            = ["keycloak/admin", "keycloak/cliente-gateway-ib"]
  chave_kms_arn       = module.chaves.chave_arn
  dias_para_recuperar = 0
}
