# Módulo documentos: o MongoDB do extrato, como Amazon DocumentDB.
#
# DocumentDB é o banco de documentos da AWS que fala o protocolo do MongoDB, mas NÃO é o MongoDB:
# é um motor próprio por baixo, e parte dos comandos e operadores falta ou se comporta diferente
# (a lista está em "Functional differences" na documentação da AWS). O README de infra/terraform
# compara com o MongoDB Atlas; a escolha aqui pesou que o extrato é uma visão de leitura (CQRS,
# ADR-0016) que usa só upsert, $set e índices simples, e pode ser reconstruída do Kafka a
# qualquer momento (scripts/reconstruir-extrato.sh). O risco de incompatibilidade é pequeno e
# reversível; o ganho é ficar tudo na mesma conta, VPC, IAM e fatura.
#
# A senha do usuário mestre é gerada e girada pela própria AWS no Secrets Manager.

resource "aws_docdb_subnet_group" "dados" {
  name        = "${var.nome}-documentos"
  description = "Sub-redes de dados"
  subnet_ids  = var.subredes
}

# TLS obrigatório e registro de auditoria (quem leu e escreveu o quê).
resource "aws_docdb_cluster_parameter_group" "extrato" {
  name        = "${var.nome}-documentos"
  family      = "docdb8.0"
  description = "Ledgerly: TLS obrigatorio e auditoria"

  parameter {
    name  = "tls"
    value = "enabled"
  }

  parameter {
    name  = "audit_logs"
    value = "enabled"
  }
}

resource "aws_security_group" "documentos" {
  name        = "${var.nome}-documentos"
  description = "DocumentDB: so o extrato entra"
  vpc_id      = var.vpc_id
  tags        = { Name = "${var.nome}-documentos" }
}

resource "aws_vpc_security_group_ingress_rule" "documentos" {
  for_each = toset(var.servicos_com_acesso)

  security_group_id            = aws_security_group.documentos.id
  referenced_security_group_id = var.sg_servicos[each.key]
  ip_protocol                  = "tcp"
  from_port                    = 27017
  to_port                      = 27017
  description                  = "so o ${each.key}"
}

resource "aws_docdb_cluster" "extrato" {
  cluster_identifier = "${var.nome}-extrato"
  engine             = "docdb"
  engine_version     = var.versao_documentdb
  master_username    = "ledgerly_admin"

  # A AWS gera a senha e a guarda no Secrets Manager. Diferente do RDS, o provedor 6.71 não
  # deixa escolher a chave desse segredo no DocumentDB: ele fica com a chave padrão da conta
  # (aws/secretsmanager) — ainda cifrado, só que sem a nossa chave própria.
  manage_master_user_password = true

  db_subnet_group_name            = aws_docdb_subnet_group.dados.name
  db_cluster_parameter_group_name = aws_docdb_cluster_parameter_group.extrato.name
  vpc_security_group_ids          = [aws_security_group.documentos.id]

  storage_encrypted = true
  kms_key_id        = var.chave_kms_arn

  backup_retention_period         = var.dias_de_backup
  preferred_backup_window         = "05:00-06:00"
  enabled_cloudwatch_logs_exports = ["audit", "profiler"]

  deletion_protection       = var.protecao_contra_exclusao
  skip_final_snapshot       = false
  final_snapshot_identifier = "${var.nome}-extrato-final"
}

resource "aws_docdb_cluster_instance" "extrato" {
  count = var.instancias

  identifier                 = "${var.nome}-extrato-${count.index}"
  cluster_identifier         = aws_docdb_cluster.extrato.id
  instance_class             = var.classe
  auto_minor_version_upgrade = true
}
