output "balde" {
  description = "Nome do balde S3 dos relatórios."
  value       = aws_s3_bucket.relatorios.bucket
}

output "balde_arn" {
  description = "ARN do balde S3 dos relatórios."
  value       = aws_s3_bucket.relatorios.arn
}

output "fila" {
  description = "Endereço (URL) da fila que avisa relatório novo."
  value       = aws_sqs_queue.relatorios_prontos.id
}

output "fila_mortas" {
  description = "Endereço (URL) da fila de mensagens mortas."
  value       = aws_sqs_queue.mortas.id
}
