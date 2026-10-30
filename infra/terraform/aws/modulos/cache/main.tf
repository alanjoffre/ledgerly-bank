# Módulo cache: o Redis do pix, como ElastiCache for Valkey.
#
# O pix usa o Redis como trava rápida contra o pedido repetido (SET ... NX PX, ADR-0018).
# Valkey é a continuação aberta (licença BSD) do Redis, mantida pela Linux Foundation desde que o
# Redis mudou de licença em 2024; fala o mesmo protocolo, e o comando do pix é o mesmo. Na AWS,
# o ElastiCache for Valkey custa cerca de 20% menos por nó que o ElastiCache for Redis OSS.
#
# A senha (*auth token*) nasce aqui, de um gerador efêmero (*ephemeral*): ela vai direto para o
# ElastiCache e para o Secrets Manager por campos "só escrita" (*write-only*, Terraform 1.11+)
# e NUNCA é gravada no estado do Terraform. Para trocá-la, suba `versao_da_senha`: as duas pontas
# recebem a nova no mesmo apply.

ephemeral "random_password" "token" {
  length  = 48
  special = false
}

resource "aws_secretsmanager_secret" "token" {
  name        = "${var.nome}/cache/token"
  description = "Senha (auth token) do Valkey do pix"
  kms_key_id  = var.chave_kms_arn
}

resource "aws_secretsmanager_secret_version" "token" {
  secret_id                = aws_secretsmanager_secret.token.id
  secret_string_wo         = ephemeral.random_password.token.result
  secret_string_wo_version = var.versao_da_senha
}

resource "aws_elasticache_subnet_group" "dados" {
  name        = "${var.nome}-cache"
  description = "Sub-redes de dados"
  subnet_ids  = var.subredes
}

resource "aws_security_group" "cache" {
  name        = "${var.nome}-cache"
  description = "Valkey: so o pix entra"
  vpc_id      = var.vpc_id
  tags        = { Name = "${var.nome}-cache" }
}

resource "aws_vpc_security_group_ingress_rule" "cache" {
  for_each = toset(var.servicos_com_acesso)

  security_group_id            = aws_security_group.cache.id
  referenced_security_group_id = var.sg_servicos[each.key]
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
  description                  = "so o ${each.key}"
}

resource "aws_elasticache_replication_group" "pix" {
  replication_group_id = "${var.nome}-pix"
  description          = "Trava contra pedido repetido do pix"
  engine               = "valkey"
  engine_version       = var.versao_valkey
  node_type            = var.tipo_de_no
  port                 = 6379

  # Uma principal e uma réplica em zonas diferentes; se a principal cair, a réplica assume.
  num_cache_clusters         = 2
  automatic_failover_enabled = true
  multi_az_enabled           = true

  subnet_group_name  = aws_elasticache_subnet_group.dados.name
  security_group_ids = [aws_security_group.cache.id]

  at_rest_encryption_enabled = true
  kms_key_id                 = var.chave_kms_arn
  transit_encryption_enabled = true
  auth_token_wo              = ephemeral.random_password.token.result
  auth_token_wo_version      = var.versao_da_senha

  snapshot_retention_limit   = 1
  auto_minor_version_upgrade = true
  apply_immediately          = false
}
