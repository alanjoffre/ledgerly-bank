output "arn" {
  description = "ARN do cluster MSK."
  value       = aws_msk_cluster.eventos.arn
}

output "nome" {
  description = "Nome do cluster MSK."
  value       = aws_msk_cluster.eventos.cluster_name
}

output "enderecos_iam" {
  description = "Endereços dos brokers para clientes com autenticação IAM (porta 9098)."
  value       = aws_msk_cluster.eventos.bootstrap_brokers_sasl_iam
}
