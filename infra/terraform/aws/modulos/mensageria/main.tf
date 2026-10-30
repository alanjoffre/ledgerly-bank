# Módulo mensageria: o Kafka do banco, como Amazon MSK provisionado.
#
# MSK (*Managed Streaming for Apache Kafka*) é o Kafka gerenciado: um *broker* (servidor do
# Kafka) por zona, e a AWS troca o que quebrar. Em modo KRaft, o Kafka 4 não precisa mais do
# ZooKeeper, e o MSK inclui os controladores sem custo extra.
#
# Autenticação por IAM (porta 9098), e não por usuário e senha: cada serviço entra com o papel IAM
# do seu pod (módulo identidades), e a permissão é POR TÓPICO — o pix só escreve em
# pix.transferencias.v1, o extrato só lê. Não existe senha para vazar nem para girar.
# O preço: cada serviço precisa da biblioteca aws-msk-iam-auth no cliente Kafka.
#
# Os tópicos não são criados aqui: o provedor da AWS não fala o protocolo do Kafka. Eles nascem
# como no compose (infra/docker/kafka/criar-topicos.sh), num Job do Kubernetes, que ainda não existe no chart (ver infra/terraform/README.md).

locals {
  brokers = length(var.subredes)
  # Fator de replicação 3 com 3 zonas; com 2 zonas, 2. Mínimo em sincronia: um a menos.
  replicacao = min(3, local.brokers)
}

resource "aws_security_group" "kafka" {
  name        = "${var.nome}-kafka"
  description = "MSK: so os servicos que publicam ou leem eventos"
  vpc_id      = var.vpc_id
  tags        = { Name = "${var.nome}-kafka" }
}

resource "aws_vpc_security_group_ingress_rule" "kafka" {
  for_each = toset(var.servicos_com_acesso)

  security_group_id            = aws_security_group.kafka.id
  referenced_security_group_id = var.sg_servicos[each.key]
  ip_protocol                  = "tcp"
  from_port                    = 9098
  to_port                      = 9098
  description                  = "IAM sobre TLS, so o ${each.key}"
}

resource "aws_msk_configuration" "ledgerly" {
  name           = "${var.nome}-kafka"
  kafka_versions = [var.versao_kafka]
  description    = "Ledgerly: topico so por codigo, replicacao em todas as zonas"

  # auto.create.topics.enable=false: um nome de tópico errado no código falha alto, em vez de
  # criar um tópico novo e vazio onde ninguém lê.
  server_properties = <<-PROPRIEDADES
    auto.create.topics.enable=false
    default.replication.factor=${local.replicacao}
    min.insync.replicas=${max(1, local.replicacao - 1)}
    num.partitions=3
    unclean.leader.election.enable=false
  PROPRIEDADES
}

resource "aws_cloudwatch_log_group" "kafka" {
  name              = "/ledgerly/${var.nome}/kafka"
  retention_in_days = var.dias_retencao_logs
}

resource "aws_msk_cluster" "eventos" {
  cluster_name           = "${var.nome}-eventos"
  kafka_version          = var.versao_kafka
  number_of_broker_nodes = local.brokers

  broker_node_group_info {
    instance_type   = var.tipo_de_broker
    client_subnets  = var.subredes
    security_groups = [aws_security_group.kafka.id]

    storage_info {
      ebs_storage_info {
        volume_size = var.disco_gb
      }
    }
  }

  configuration_info {
    arn      = aws_msk_configuration.ledgerly.arn
    revision = aws_msk_configuration.ledgerly.latest_revision
  }

  client_authentication {
    unauthenticated = false
    sasl {
      iam = true
    }
  }

  encryption_info {
    encryption_at_rest_kms_key_arn = var.chave_kms_arn
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.kafka.name
      }
    }
  }

  tags = { Name = "${var.nome}-eventos" }
}
