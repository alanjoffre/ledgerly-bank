output "endereco" {
  description = "Endereço do DocumentDB (TLS)."
  value       = aws_docdb_cluster.extrato.endpoint
}

output "segredo" {
  description = "ARN do segredo do usuário mestre (gerado e girado pela AWS)."
  value       = aws_docdb_cluster.extrato.master_user_secret[0].secret_arn
}
