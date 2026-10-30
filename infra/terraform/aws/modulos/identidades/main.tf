# Módulo identidades: um papel IAM por serviço, entregue ao pod pelo EKS Pod Identity.
#
# IAM (*Identity and Access Management*) decide quem pode chamar qual API da AWS. Em vez de uma
# chave de acesso guardada num segredo (que vaza e não expira), cada pod recebe credenciais
# temporárias do papel do seu serviço, pela conta de serviço (*ServiceAccount*) do Kubernetes.
#
# O que cada papel pode, e só isso (privilégio mínimo):
# - ler os SEUS segredos no Secrets Manager (e usar a chave KMS para decifrá-los, só por ele);
# - entrar no SEU banco com token IAM (rds-db:connect), como o usuário com o nome do serviço;
# - no Kafka: escrever nos tópicos que ele publica e ler os que ele consome (ADR-0013, ADR-0014);
# - no S3: só a conciliação escreve relatórios.
#
# Os ARNs que pedem o número da conta são montados a partir do ARN de um recurso criado (que
# já traz a conta), e não de uma consulta à AWS: assim o plan roda sem conta.

locals {
  conta_rds = { for s, arn in var.bancos_arns : s => element(split(":", arn), 4) }
  # arn:aws:kafka:<regiao>:<conta>:cluster/<nome>/<uuid> → prefixos de tópico e de grupo
  prefixo_topico = replace(var.kafka_arn, ":cluster/", ":topic/")
  prefixo_grupo  = replace(var.kafka_arn, ":cluster/", ":group/")

  bancos_de = {
    for s in var.servicos : s => concat(
      contains(keys(var.bancos_ids), s) ? [s] : [],
      [for banco, leitores in var.acessos_extras : banco if contains(leitores, s)],
    )
  }
}

data "aws_iam_policy_document" "confiar_no_eks" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole", "sts:TagSession"]
    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "servico" {
  for_each = toset(var.servicos)

  name               = "${var.nome}-servico-${each.key}"
  description        = "Papel do servico ${each.key} no EKS (Pod Identity)"
  assume_role_policy = data.aws_iam_policy_document.confiar_no_eks.json
  tags               = { Servico = each.key }
}

data "aws_iam_policy_document" "servico" {
  for_each = toset(var.servicos)

  dynamic "statement" {
    for_each = length(lookup(var.segredos_por_servico, each.key, [])) > 0 ? [1] : []
    content {
      sid       = "LerOsProprioSegredos"
      actions   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
      resources = var.segredos_por_servico[each.key]
    }
  }

  dynamic "statement" {
    for_each = length(lookup(var.segredos_por_servico, each.key, [])) > 0 ? [1] : []
    content {
      sid       = "DecifrarOsProprioSegredos"
      actions   = ["kms:Decrypt"]
      resources = [var.chave_kms_arn]
      condition {
        test     = "StringEquals"
        variable = "kms:ViaService"
        values   = ["secretsmanager.${var.regiao}.amazonaws.com"]
      }
    }
  }

  # O próprio banco e, para a conciliação, os bancos que ela lê (acessos_extras).
  dynamic "statement" {
    for_each = length(local.bancos_de[each.key]) > 0 ? [1] : []
    content {
      sid     = "EntrarNosSeusBancos"
      actions = ["rds-db:connect"]
      resources = [
        for b in local.bancos_de[each.key] :
        "arn:aws:rds-db:${var.regiao}:${local.conta_rds[b]}:dbuser:${var.bancos_ids[b]}/${each.key}"
      ]
    }
  }

  dynamic "statement" {
    for_each = contains(keys(var.kafka), each.key) ? [1] : []
    content {
      sid = "ConectarNoKafka"
      # Os produtores usam enable.idempotence=true (ADR-0013), e o MSK pede esta ação para isso.
      actions = concat(
        ["kafka-cluster:Connect"],
        length(try(var.kafka[each.key].escreve, [])) > 0 ? ["kafka-cluster:WriteDataIdempotently"] : [],
      )
      resources = [var.kafka_arn]
    }
  }

  dynamic "statement" {
    for_each = length(try(var.kafka[each.key].escreve, [])) > 0 ? [1] : []
    content {
      sid       = "PublicarEventos"
      actions   = ["kafka-cluster:WriteData", "kafka-cluster:DescribeTopic"]
      resources = [for t in var.kafka[each.key].escreve : "${local.prefixo_topico}/${t}"]
    }
  }

  dynamic "statement" {
    for_each = length(try(var.kafka[each.key].le, [])) > 0 ? [1] : []
    content {
      sid       = "LerEventos"
      actions   = ["kafka-cluster:ReadData", "kafka-cluster:DescribeTopic"]
      resources = [for t in var.kafka[each.key].le : "${local.prefixo_topico}/${t}"]
    }
  }

  dynamic "statement" {
    for_each = length(try(var.kafka[each.key].le, [])) > 0 ? [1] : []
    content {
      sid       = "GrupoDeConsumoProprio"
      actions   = ["kafka-cluster:AlterGroup", "kafka-cluster:DescribeGroup"]
      resources = ["${local.prefixo_grupo}/${each.key}*"]
    }
  }

  dynamic "statement" {
    for_each = contains(var.escrevem_relatorios, each.key) ? [1] : []
    content {
      sid       = "GravarRelatorios"
      actions   = ["s3:PutObject", "s3:GetObject"]
      resources = ["${var.balde_arn}/conciliacao/*"]
    }
  }

  dynamic "statement" {
    for_each = contains(var.escrevem_relatorios, each.key) ? [1] : []
    content {
      sid       = "CifrarRelatorios"
      actions   = ["kms:GenerateDataKey"]
      resources = [var.chave_kms_arn]
    }
  }
}

resource "aws_iam_role_policy" "servico" {
  for_each = toset(var.servicos)

  name   = "permissoes-do-${each.key}"
  role   = aws_iam_role.servico[each.key].id
  policy = data.aws_iam_policy_document.servico[each.key].json
}

# O vínculo: pods com a ServiceAccount do serviço no namespace do banco recebem o papel dele.
locals {
  vinculos = merge([
    for s in var.servicos : { for conta in lookup(var.contas_de_servico, s, [s]) : conta => s }
  ]...)
}

resource "aws_eks_pod_identity_association" "servico" {
  for_each = local.vinculos

  cluster_name    = var.cluster
  namespace       = var.namespace
  service_account = each.key
  role_arn        = aws_iam_role.servico[each.value].arn
}
