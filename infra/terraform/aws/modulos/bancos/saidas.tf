output "enderecos" {
  description = "Endereço (host:porta) de cada banco."
  value       = { for s, b in aws_db_instance.banco : s => b.endpoint }
}

output "segredos" {
  description = "ARN do segredo do usuário mestre de cada banco (gerado e girado pelo RDS)."
  value       = { for s, b in aws_db_instance.banco : s => b.master_user_secret[0].secret_arn }
}

output "ids_de_recurso" {
  description = "Id de recurso de cada banco (usado na permissão rds-db:connect)."
  value       = { for s, b in aws_db_instance.banco : s => b.resource_id }
}

output "arns" {
  description = "ARN de cada banco."
  value       = { for s, b in aws_db_instance.banco : s => b.arn }
}
