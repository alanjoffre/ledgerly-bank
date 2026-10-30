# Módulo bancos: um RDS PostgreSQL por serviço (o padrão *database per service*, levado ao fim).
#
# RDS (*Relational Database Service*) é o PostgreSQL gerenciado: a AWS cuida de backup, correção
# de versão menor e troca para a réplica em outra zona (*Multi-AZ*) se a principal cair.
#
# Por que uma instância por serviço, e não uma instância com vários bancos (como no compose
# local): o README de infra/terraform explica em detalhe; em uma linha, uma consulta pesada do
# assistente não pode deixar o razão lento, e o grupo de segurança de cada instância só abre
# para o serviço dono. O pgvector (busca por vetores do assistente) é uma extensão que o RDS
# PostgreSQL já traz: `CREATE EXTENSION vector` na migração do assistente, sem instância especial.
#
# A senha do usuário mestre é gerada e guardada pelo próprio RDS no Secrets Manager
# (manage_master_user_password), com rotação automática: ela não passa pelo Terraform, não fica
# no estado (*state*) e ninguém precisa conhecê-la.

resource "aws_db_subnet_group" "dados" {
  name        = "${var.nome}-bancos"
  description = "Sub-redes de dados (sem rota para a internet)"
  subnet_ids  = var.subredes
}

# Parâmetros comuns: TLS obrigatório e registro de consultas lentas.
resource "aws_db_parameter_group" "postgres" {
  name        = "${var.nome}-postgres18"
  family      = "postgres18"
  description = "Ledgerly: TLS obrigatorio e registro de consultas lentas"

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  parameter {
    name  = "log_min_duration_statement"
    value = "500"
  }

  parameter {
    name  = "log_connections"
    value = "1"
  }
}

resource "aws_security_group" "banco" {
  for_each = var.bancos

  name        = "${var.nome}-banco-${each.key}"
  description = "PostgreSQL do servico ${each.key}: so o servico dono entra"
  vpc_id      = var.vpc_id
  tags        = { Name = "${var.nome}-banco-${each.key}", Servico = each.key }
}

resource "aws_vpc_security_group_ingress_rule" "banco" {
  for_each = var.bancos

  security_group_id            = aws_security_group.banco[each.key].id
  referenced_security_group_id = var.sg_servicos[each.key]
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  description                  = "so o servico ${each.key}"
}

# Quem mais lê um banco, além do dono: a conciliação lê o banco do pix e o do razão (ADR-0022).
locals {
  acessos_extras = flatten([
    for banco, leitores in var.acessos_extras : [
      for leitor in leitores : { banco = banco, leitor = leitor }
    ]
  ])
}

resource "aws_vpc_security_group_ingress_rule" "extra" {
  for_each = { for a in local.acessos_extras : "${a.leitor}-em-${a.banco}" => a }

  security_group_id            = aws_security_group.banco[each.value.banco].id
  referenced_security_group_id = var.sg_servicos[each.value.leitor]
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  description                  = "${each.value.leitor} le o banco ${each.value.banco}"
}

resource "aws_db_instance" "banco" {
  for_each = var.bancos

  identifier     = "${var.nome}-${each.key}"
  engine         = "postgres"
  engine_version = var.versao_postgres
  instance_class = each.value.classe
  db_name        = each.key
  username       = "ledgerly_admin"

  manage_master_user_password   = true
  master_user_secret_kms_key_id = var.chave_kms_arn
  # O serviço entra com um usuário próprio e um token IAM de 15 minutos, sem senha guardada.
  iam_database_authentication_enabled = true

  allocated_storage     = each.value.armazenamento_gb
  max_allocated_storage = each.value.armazenamento_gb * 4
  storage_type          = "gp3"
  storage_encrypted     = true
  kms_key_id            = var.chave_kms_arn

  db_subnet_group_name   = aws_db_subnet_group.dados.name
  vpc_security_group_ids = [aws_security_group.banco[each.key].id]
  parameter_group_name   = aws_db_parameter_group.postgres.name
  publicly_accessible    = false
  multi_az               = each.value.multi_az
  ca_cert_identifier     = "rds-ca-rsa2048-g1"

  backup_retention_period    = each.value.dias_de_backup
  backup_window              = "05:00-06:00"
  maintenance_window         = "sun:06:30-sun:07:30"
  copy_tags_to_snapshot      = true
  auto_minor_version_upgrade = true

  performance_insights_enabled          = true
  performance_insights_kms_key_id       = var.chave_kms_arn
  performance_insights_retention_period = 7
  enabled_cloudwatch_logs_exports       = ["postgresql", "upgrade"]

  # Apagar um banco exige dois passos de propósito: desligar a proteção e, ao apagar, fica a cópia final.
  deletion_protection       = var.protecao_contra_exclusao
  skip_final_snapshot       = false
  final_snapshot_identifier = "${var.nome}-${each.key}-final"

  tags = { Name = "${var.nome}-${each.key}", Servico = each.key }
}
