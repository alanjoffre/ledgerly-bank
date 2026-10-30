output "endereco" {
  description = "Endereço principal do Valkey (TLS)."
  value       = aws_elasticache_replication_group.pix.primary_endpoint_address
}

output "segredo" {
  description = "ARN do segredo com a senha do Valkey."
  value       = aws_secretsmanager_secret.token.arn
}
