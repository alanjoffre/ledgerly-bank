output "papeis" {
  description = "ARN do papel IAM de cada serviço."
  value       = { for s, p in aws_iam_role.servico : s => p.arn }
}
