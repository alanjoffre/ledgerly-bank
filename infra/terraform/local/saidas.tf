# O scripts/nuvem.sh lê estas saídas para conferir o resultado com o aws-cli.

output "balde" {
  description = "Nome do balde S3 dos relatórios no simulador."
  value       = module.armazenamento.balde
}

output "fila" {
  description = "Endereço da fila que avisa relatório novo."
  value       = module.armazenamento.fila
}

output "fila_mortas" {
  description = "Endereço da fila de mensagens mortas."
  value       = module.armazenamento.fila_mortas
}

output "segredos" {
  description = "ARN de cada segredo criado no simulador."
  value       = module.segredos.arns
}
